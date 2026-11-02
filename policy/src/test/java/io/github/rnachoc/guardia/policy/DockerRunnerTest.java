package io.github.rnachoc.guardia.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Written before the runner: it restarts a Compose service through the Docker API, finding its container by
 * label, and refuses at the dry run whatever it cannot do, instead of pretending.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class DockerRunnerTest {

    private Path socket;
    private ServerSocketChannel server;
    private Thread loop;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String containers = "[{\"Id\":\"abc123\"}]";

    /**
     * Starts a stand-in for the Docker API on a Unix socket.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @BeforeEach
    void start() throws IOException {
        socket = Files.createTempDirectory("docker-").resolve("docker.sock");
        server = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket));
        loop = Thread.ofVirtual().start(() -> {
            while (server.isOpen()) {
                try (SocketChannel client = server.accept()) {
                    ByteBuffer buffer = ByteBuffer.allocate(8192);
                    client.read(buffer);
                    String request = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
                    String line = request.lines().findFirst().orElse("");
                    requests.add(line);
                    bodies.add(request.substring(request.indexOf("\r\n\r\n") + 4));
                    String response = line.startsWith("GET /containers/json")
                            ? "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\n\r\n" + containers
                            : line.matches("POST /containers/[^/]+/exec .*") ? "HTTP/1.0 201 Created\r\n\r\n{\"Id\":\"exec1\"}"
                            : line.startsWith("POST /exec/") ? "HTTP/1.0 200 OK\r\n\r\n"
                            : "HTTP/1.0 204 No Content\r\n\r\n";
                    client.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    /**
     * Stops the stand-in.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @AfterEach
    void stop() throws IOException {
        server.close();
        loop.interrupt();
    }

    /**
     * Restarting a service finds its container by Compose labels, then restarts that container.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void restartsTheServiceContainer() {
        DockerRunner runner = new DockerRunner(socket, "guardia");
        assertThat(runner.dryRun("restart_service", "inventory")).isTrue();
        runner.run("restart_service", "inventory");
        assertThat(requests.getFirst()).startsWith("GET /containers/json?all=1&filters=")
                .contains("com.docker.compose.service%3Dinventory").contains("com.docker.compose.project%3Dguardia");
        assertThat(requests.getLast()).startsWith("POST /containers/abc123/restart");
    }

    /**
     * An action it does not implement, or a service with no container, fails the dry run, so nothing runs.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesWhatItCannotDo() {
        DockerRunner runner = new DockerRunner(socket, "guardia");
        assertThat(runner.dryRun("failover", "payments")).isFalse();
        containers = "[]";
        assertThat(runner.dryRun("restart_service", "inventory")).isFalse();
        assertThat(requests).noneMatch(r -> r.startsWith("POST"));
    }

    /**
     * A Docker daemon that cannot be reached fails the dry run.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesWhenDockerIsUnreachable() {
        assertThat(new DockerRunner(socket.resolveSibling("missing.sock"), "guardia").dryRun("restart_service", "inventory")).isFalse();
    }

    /**
     * Pausing the consumer freezes the notifier's container, and resuming, or reverting a pause, unfreezes it.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void pausesAndResumesTheConsumer() {
        DockerRunner runner = new DockerRunner(socket, "guardia");
        assertThat(runner.dryRun("pause_consumer", "notifier")).isTrue();
        runner.run("pause_consumer", "notifier");
        assertThat(requests.getLast()).startsWith("POST /containers/abc123/pause");
        runner.run("resume_consumer", "notifier");
        assertThat(requests.getLast()).startsWith("POST /containers/abc123/unpause");
        runner.revert("resume_consumer", "notifier");
        assertThat(requests.getLast()).startsWith("POST /containers/abc123/unpause");
    }

    /**
     * Clearing the catalog's cache deletes its keys in Redis, through an exec in the Redis container, and only there.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void clearsTheCatalogCacheInRedis() {
        DockerRunner runner = new DockerRunner(socket, "guardia");
        assertThat(runner.dryRun("clear_cache", "catalog")).isTrue();
        assertThat(runner.dryRun("clear_cache", "cart")).isFalse();
        requests.clear();
        bodies.clear();
        runner.run("clear_cache", "catalog");
        assertThat(requests.getFirst()).contains("com.docker.compose.service%3Dredis");
        assertThat(requests).anyMatch(r -> r.startsWith("POST /containers/abc123/exec"));
        assertThat(String.join("\n", bodies)).contains("products::*");
        assertThat(requests.getLast()).startsWith("POST /exec/exec1/start");
    }
}
