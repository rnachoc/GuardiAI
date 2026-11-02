package io.github.rnachoc.guardia.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Acts on the stack through the Docker Engine API over its Unix socket. It implements only what it can do
 * truthfully: restarting a Compose service's container, pausing and unpausing it (which stops and resumes the
 * consumer inside, while its offsets stay in Kafka), and deleting the catalog's cached products in Redis. Every
 * other action fails its dry run, so the executor never believes something ran that did not. This is the only class with the power to change the
 * system, and it runs only inside the policy service (ADR 18).
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class DockerRunner implements Executor.Runner {

    private static final Set<String> SUPPORTED = Set.of("restart_service", "pause_consumer", "resume_consumer", "clear_cache");
    /** The Redis keys of the catalog's Spring cache, and the command that deletes them inside the Redis container. */
    private static final String CLEAR_CATALOG_CACHE = "redis-cli --scan --pattern 'products::*' | xargs -r redis-cli del";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path socket;
    private final String project;

    /**
     * Creates the runner.
     *
     * @param socket the Docker daemon's socket
     * @param project the Compose project whose services it may act on
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public DockerRunner(Path socket, String project) {
        this.socket = socket;
        this.project = project;
    }

    /**
     * Checks that the action is one this runner implements and that the service has a container.
     *
     * @param action the action
     * @param target the Compose service
     * @return {@code true} when it could run
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public boolean dryRun(String action, String target) {
        try {
            if ("clear_cache".equals(action)) {
                return "catalog".equals(target) && container("redis").isPresent();
            }
            return SUPPORTED.contains(action) && container(target).isPresent();
        } catch (UncheckedIOException unreachable) {
            return false;
        }
    }

    /**
     * Runs the action: restarts the container, pauses or unpauses it (which stops or resumes the consumer inside),
     * or deletes the catalog's cached products in Redis.
     *
     * @param action the action, which must be supported
     * @param target the Compose service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void run(String action, String target) {
        switch (action) {
            case "restart_service" -> post(target, "/restart?t=5", 204);
            case "pause_consumer" -> post(target, "/pause", 204);
            case "resume_consumer" -> post(target, "/unpause", 204);
            case "clear_cache" -> clearCatalogCache(target);
            default -> throw new IllegalArgumentException(action + " is not implemented by this runner");
        }
    }

    /**
     * Reverts an action: a pause is reverted by resuming. Restarting and clearing a cache have nothing to revert.
     *
     * @param revertAction the catalog's auto-revert
     * @param target the Compose service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void revert(String revertAction, String target) {
        if (!"resume_consumer".equals(revertAction)) {
            throw new IllegalArgumentException(revertAction + " is not implemented by this runner");
        }
        post(target, "/unpause", 204);
    }

    /**
     * Sends a POST to one of a service's container endpoints and checks the status.
     *
     * @param service the Compose service
     * @param endpoint the endpoint under the container, with its query
     * @param expected the status that means success
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private void post(String service, String endpoint, int expected) {
        String id = container(service).orElseThrow(() -> new IllegalStateException("no container for " + service));
        int status = request("POST", "/containers/" + id + endpoint, "").status();
        if (status != expected) {
            throw new IllegalStateException("Docker answered " + status + " to " + endpoint + " on " + service);
        }
    }

    /**
     * Deletes the catalog's cached products by running redis-cli in the Redis container.
     *
     * @param target the cache's owner, which must be catalog
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private void clearCatalogCache(String target) {
        if (!"catalog".equals(target)) {
            throw new IllegalArgumentException("only the catalog's cache can be cleared");
        }
        String redis = container("redis").orElseThrow(() -> new IllegalStateException("no container for redis"));
        try {
            String body = JSON.writeValueAsString(java.util.Map.of("Cmd", java.util.List.of("sh", "-c", CLEAR_CATALOG_CACHE),
                    "AttachStdout", false, "AttachStderr", false));
            Response created = request("POST", "/containers/" + redis + "/exec", body);
            if (created.status() != 201) {
                throw new IllegalStateException("Docker answered " + created.status() + " to creating the exec");
            }
            String exec = JSON.readTree(created.body()).path("Id").asText();
            int started = request("POST", "/exec/" + exec + "/start", "{\"Detach\":true}").status();
            if (started != 200) {
                throw new IllegalStateException("Docker answered " + started + " to starting the exec");
            }
        } catch (IOException malformed) {
            throw new UncheckedIOException(malformed);
        }
    }

    /**
     * Finds a Compose service's container by its labels.
     *
     * @param service the service
     * @return the container's ID, or empty when there is none
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private Optional<String> container(String service) {
        String filters = "{\"label\":[\"com.docker.compose.project=" + project + "\",\"com.docker.compose.service=" + service + "\"]}";
        Response response = request("GET", "/containers/json?all=1&filters=" + URLEncoder.encode(filters, StandardCharsets.UTF_8), "");
        try {
            JsonNode list = JSON.readTree(response.body());
            return list.isArray() && list.size() == 1 ? Optional.of(list.get(0).path("Id").asText()) : Optional.empty();
        } catch (IOException malformed) {
            return Optional.empty();
        }
    }

    /**
     * Sends one HTTP/1.0 request over the socket and reads the whole answer, which HTTP/1.0 sends unchunked.
     *
     * @param method the HTTP method
     * @param path the path and query
     * @param body a JSON body, or empty
     * @return the status and body
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private Response request(String method, String path, String body) {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            byte[] content = body.getBytes(StandardCharsets.UTF_8);
            String request = method + " " + path + " HTTP/1.0\r\nHost: docker\r\nContent-Type: application/json\r\nContent-Length: "
                    + content.length + "\r\n\r\n" + body;
            channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            ByteArrayOutputStream answer = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) > 0) {
                answer.write(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
            String text = answer.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(text.substring(9, 12));
            int split = text.indexOf("\r\n\r\n");
            return new Response(status, split < 0 ? "" : text.substring(split + 4));
        } catch (IOException unreachable) {
            throw new UncheckedIOException(unreachable);
        }
    }

    /**
     * An answer from the Docker API.
     *
     * @param status the HTTP status
     * @param body the body
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private record Response(int status, String body) {
    }
}
