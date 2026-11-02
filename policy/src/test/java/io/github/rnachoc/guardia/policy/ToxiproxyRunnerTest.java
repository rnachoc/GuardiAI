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
 * Written before the runner: failing payments over points checkout's proxy at the standby, through
 * Toxiproxy's API, and failing back points it at the primary again.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class ToxiproxyRunnerTest {

    private HttpServer toxiproxy;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    /**
     * Starts a stand-in for Toxiproxy's API that knows the checkout-payments proxy.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @BeforeEach
    void start() throws IOException {
        toxiproxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        toxiproxy.createContext("/proxies/checkout-payments", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"name\":\"checkout-payments\",\"upstream\":\"payments:8080\",\"enabled\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        toxiproxy.start();
    }

    /**
     * Stops the stand-in.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @AfterEach
    void stop() {
        toxiproxy.stop(0);
    }

    /**
     * Builds a runner pointed at the stand-in.
     *
     * @return the runner
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private ToxiproxyRunner runner() {
        return new ToxiproxyRunner("http://127.0.0.1:" + toxiproxy.getAddress().getPort());
    }

    /**
     * Failover points the proxy at the standby; failing back points it at the primary.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void failsOverToTheStandbyAndBack() {
        ToxiproxyRunner runner = runner();
        assertThat(runner.dryRun("failover", "payments")).isTrue();
        runner.run("failover", "payments");
        assertThat(calls.getLast()).isEqualTo("POST {\"upstream\":\"payments-standby:8080\"}");
        runner.revert("fail_back", "payments");
        assertThat(calls.getLast()).isEqualTo("POST {\"upstream\":\"payments:8080\"}");
    }

    /**
     * Anything else, or a Toxiproxy that cannot be reached, fails the dry run.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesWhatItCannotDo() {
        assertThat(runner().dryRun("failover", "inventory")).isFalse();
        assertThat(runner().dryRun("restart_service", "payments")).isFalse();
        String dead = "http://127.0.0.1:" + toxiproxy.getAddress().getPort();
        toxiproxy.stop(0);
        assertThat(new ToxiproxyRunner(dead).dryRun("failover", "payments")).isFalse();
        assertThat(calls).noneMatch(c -> c.startsWith("POST"));
    }

    /**
     * The router sends each action, and each revert, to the runner that implements it, and refuses the rest.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void theRouterSendsEachActionToItsRunner() {
        RoutingRunner router = new RoutingRunner(java.util.Map.of("failover", runner()), java.util.Map.of("fail_back", runner()));
        assertThat(router.dryRun("failover", "payments")).isTrue();
        assertThat(router.dryRun("toggle_flag", "checkout")).isFalse();
        router.run("failover", "payments");
        router.revert("fail_back", "payments");
        assertThat(calls).filteredOn(c -> c.startsWith("POST")).hasSize(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> router.run("toggle_flag", "checkout")).isInstanceOf(IllegalArgumentException.class);
    }
}
