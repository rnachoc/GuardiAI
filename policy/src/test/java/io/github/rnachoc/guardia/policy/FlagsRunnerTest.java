package io.github.rnachoc.guardia.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Written before the runner: toggling a service's flag turns off its latest release in the flags service, and
 * toggling back turns it on again.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class FlagsRunnerTest {

    private HttpServer flags;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /**
     * Starts a stand-in for the flags service that knows only checkout's flag.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @BeforeEach
    void start() throws IOException {
        flags = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        flags.createContext("/flags/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + path + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = path.equals("/flags/checkout.latest_release") ? 200 : 404;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        flags.start();
    }

    /**
     * Stops the stand-in.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @AfterEach
    void stop() {
        flags.stop(0);
    }

    /**
     * Toggling turns the flag off, and toggling back turns it on.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void turnsTheLatestReleaseOffAndOn() {
        FlagsRunner runner = new FlagsRunner("http://127.0.0.1:" + flags.getAddress().getPort());
        assertThat(runner.dryRun("toggle_flag", "checkout")).isTrue();
        runner.run("toggle_flag", "checkout");
        assertThat(calls.getLast()).isEqualTo("PUT /flags/checkout.latest_release {\"enabled\":false}");
        runner.revert("toggle_back", "checkout");
        assertThat(calls.getLast()).isEqualTo("PUT /flags/checkout.latest_release {\"enabled\":true}");
    }

    /**
     * A service without a flag, another action, or a flags service that is down fails the dry run.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesWhatItCannotDo() {
        String url = "http://127.0.0.1:" + flags.getAddress().getPort();
        assertThat(new FlagsRunner(url).dryRun("toggle_flag", "cart")).isFalse();
        assertThat(new FlagsRunner(url).dryRun("restart_service", "checkout")).isFalse();
        flags.stop(0);
        assertThat(new FlagsRunner(url).dryRun("toggle_flag", "checkout")).isFalse();
        assertThat(calls).noneMatch(c -> c.startsWith("PUT"));
    }
}
