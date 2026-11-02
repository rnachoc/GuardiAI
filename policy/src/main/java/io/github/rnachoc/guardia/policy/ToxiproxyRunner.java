package io.github.rnachoc.guardia.policy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Fails payments over to its standby, and back, by pointing checkout's connection to payments, which runs
 * through Toxiproxy, at another upstream. Nothing restarts and no client is reconfigured: new connections
 * simply reach the standby.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class ToxiproxyRunner implements Executor.Runner {

    /** For each target that can fail over: its proxy, its primary and its standby. */
    private static final Map<String, String[]> ROUTES = Map.of("payments", new String[] {"checkout-payments", "payments:8080", "payments-standby:8080"});

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * Creates the runner.
     *
     * @param baseUrl Toxiproxy's API
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public ToxiproxyRunner(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Checks that the action is a failover of a known target and that its proxy exists.
     *
     * @param action the action
     * @param target the service
     * @return {@code true} when it could run
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public boolean dryRun(String action, String target) {
        if (!"failover".equals(action) || !ROUTES.containsKey(target)) {
            return false;
        }
        try {
            return send("GET", ROUTES.get(target)[0], null) == 200;
        } catch (IOException unreachable) {
            return false;
        }
    }

    /**
     * Points the target's proxy at its standby.
     *
     * @param action the action, which must be failover
     * @param target the service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void run(String action, String target) {
        if (!"failover".equals(action)) {
            throw new IllegalArgumentException(action + " is not implemented by this runner");
        }
        point(target, 2);
    }

    /**
     * Points the target's proxy back at its primary.
     *
     * @param revertAction the catalog's auto-revert, which must be fail_back
     * @param target the service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void revert(String revertAction, String target) {
        if (!"fail_back".equals(revertAction)) {
            throw new IllegalArgumentException(revertAction + " is not implemented by this runner");
        }
        point(target, 1);
    }

    /**
     * Sets the proxy's upstream to the primary or the standby.
     *
     * @param target the service
     * @param which 1 for the primary, 2 for the standby
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private void point(String target, int which) {
        String[] route = ROUTES.get(target);
        if (route == null) {
            throw new IllegalArgumentException(target + " has no standby");
        }
        try {
            int status = send("POST", route[0], "{\"upstream\":\"" + route[which] + "\"}");
            if (status != 200) {
                throw new IllegalStateException("Toxiproxy answered " + status + " to repointing " + route[0]);
            }
        } catch (IOException unreachable) {
            throw new IllegalStateException("Toxiproxy cannot be reached: " + unreachable.getMessage(), unreachable);
        }
    }

    /**
     * Sends one request to a proxy's endpoint.
     *
     * @param method GET or POST
     * @param proxy the proxy's name
     * @param body the JSON body, or {@code null}
     * @return the status
     * @throws IOException when Toxiproxy cannot be reached
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private int send(String method, String proxy, String body) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/proxies/" + proxy)).timeout(Duration.ofSeconds(5));
        request = body == null ? request.GET() : request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        }
    }
}
