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
 * truthfully: restarting a Compose service's container. Every other action fails its dry run, so the
 * executor never believes something ran that did not. This is the only class with the power to change the
 * system, and it runs only inside the policy service (ADR 18).
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class DockerRunner implements Executor.Runner {

    private static final Set<String> SUPPORTED = Set.of("restart_service");
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
            return SUPPORTED.contains(action) && container(target).isPresent();
        } catch (UncheckedIOException unreachable) {
            return false;
        }
    }

    /**
     * Restarts the service's container.
     *
     * @param action the action, which must be supported
     * @param target the Compose service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void run(String action, String target) {
        if (!SUPPORTED.contains(action)) {
            throw new IllegalArgumentException(action + " is not implemented by this runner");
        }
        String id = container(target).orElseThrow(() -> new IllegalStateException("no container for " + target));
        int status = request("POST", "/containers/" + id + "/restart?t=5").status();
        if (status != 204) {
            throw new IllegalStateException("Docker answered " + status + " to restarting " + target);
        }
    }

    /**
     * Reverts an action. Restarting has nothing to revert, and nothing else is implemented.
     *
     * @param revertAction the catalog's auto-revert
     * @param target the Compose service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void revert(String revertAction, String target) {
        throw new IllegalArgumentException(revertAction + " is not implemented by this runner");
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
        Response response = request("GET", "/containers/json?all=1&filters=" + URLEncoder.encode(filters, StandardCharsets.UTF_8));
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
     * @return the status and body
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private Response request(String method, String path) {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            String request = method + " " + path + " HTTP/1.0\r\nHost: docker\r\nContent-Length: 0\r\n\r\n";
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
