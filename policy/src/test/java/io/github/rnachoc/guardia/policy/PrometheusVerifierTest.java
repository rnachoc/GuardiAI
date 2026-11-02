package io.github.rnachoc.guardia.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Written before the verifier: it asks Prometheus for a query's value and checks it against the catalog's
 * condition. An empty result counts as zero, and a Prometheus that cannot answer never counts as recovered.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class PrometheusVerifierTest {

    private HttpServer prometheus;
    private String body;
    private final List<String> queries = new ArrayList<>();

    /**
     * Starts a stand-in for Prometheus's query API that answers with {@link #body}.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @BeforeEach
    void start() throws IOException {
        prometheus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        prometheus.createContext("/api/v1/query", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        prometheus.start();
    }

    /**
     * Stops the stand-in.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @AfterEach
    void stop() {
        prometheus.stop(0);
    }

    /**
     * Builds a verifier pointed at the stand-in.
     *
     * @return the verifier
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private PrometheusVerifier verifier() {
        return new PrometheusVerifier("http://127.0.0.1:" + prometheus.getAddress().getPort());
    }

    /**
     * Answers with one sample of the given value.
     *
     * @param value the sample's value
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private void value(String value) {
        body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{},\"value\":[1,\"" + value + "\"]}]}}";
    }

    /**
     * Each operator compares the sample as written, and the query reaches Prometheus encoded.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void comparesTheValueWithTheCondition() {
        value("1.5");
        assertThat(verifier().holds("sum(rate(x_total[1m]))", "> 0")).isTrue();
        assertThat(verifier().holds("x", "< 0.3")).isFalse();
        assertThat(verifier().holds("x", ">= 1.5")).isTrue();
        assertThat(verifier().holds("x", "== 1.5")).isTrue();
        assertThat(queries.getFirst()).contains("query=sum%28rate%28x_total%5B1m%5D%29%29");
    }

    /**
     * An empty result counts as zero: a service that serves nothing reports nothing.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void readsAnEmptyResultAsZero() {
        body = "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
        assertThat(verifier().holds("x", "== 0")).isTrue();
        assertThat(verifier().holds("x", "> 0")).isFalse();
    }

    /**
     * A failed or unreachable Prometheus never counts as recovered.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void neverCountsAFailureAsRecovered() {
        body = "{\"status\":\"error\",\"error\":\"bad query\"}";
        assertThat(verifier().holds("x", ">= 0")).isFalse();
        assertThat(new PrometheusVerifier("http://127.0.0.1:1").holds("x", ">= 0")).isFalse();
    }

    /**
     * A condition the catalog could not mean is refused outright.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void refusesAMalformedCondition() {
        value("1");
        assertThatThrownBy(() -> verifier().holds("x", "about 1")).isInstanceOf(IllegalArgumentException.class);
    }
}
