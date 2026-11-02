package io.github.rnachoc.guardia.policy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Toggles a service's feature flag in Mercadillo's flags service: toggle_flag turns off the flag of the
 * service's latest release, the usual first move when a release misbehaves, and toggle_back turns it on again.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class FlagsRunner implements Executor.Runner {

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * Creates the runner.
     *
     * @param baseUrl the flags service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public FlagsRunner(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Names the flag of a service's latest release.
     *
     * @param target the service
     * @return the flag's name
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    static String flagOf(String target) {
        return target + ".latest_release";
    }

    /**
     * Checks that the action is toggle_flag and that the service has a flag.
     *
     * @param action the action
     * @param target the service
     * @return {@code true} when it could run
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public boolean dryRun(String action, String target) {
        if (!"toggle_flag".equals(action) || !target.matches("[a-z]{1,40}")) {
            return false;
        }
        try {
            return send("GET", flagOf(target), null) == 200;
        } catch (IOException unreachable) {
            return false;
        }
    }

    /**
     * Turns the flag of the service's latest release off.
     *
     * @param action the action, which must be toggle_flag
     * @param target the service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void run(String action, String target) {
        if (!"toggle_flag".equals(action)) {
            throw new IllegalArgumentException(action + " is not implemented by this runner");
        }
        set(target, false);
    }

    /**
     * Turns the flag on again.
     *
     * @param revertAction the catalog's auto-revert, which must be toggle_back
     * @param target the service
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public void revert(String revertAction, String target) {
        if (!"toggle_back".equals(revertAction)) {
            throw new IllegalArgumentException(revertAction + " is not implemented by this runner");
        }
        set(target, true);
    }

    /**
     * Sets the flag and checks the answer.
     *
     * @param target the service
     * @param enabled the flag's new state
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private void set(String target, boolean enabled) {
        try {
            int status = send("PUT", flagOf(target), "{\"enabled\":" + enabled + "}");
            if (status != 200) {
                throw new IllegalStateException("the flags service answered " + status + " to setting " + flagOf(target));
            }
        } catch (IOException unreachable) {
            throw new IllegalStateException("the flags service cannot be reached: " + unreachable.getMessage(), unreachable);
        }
    }

    /**
     * Sends one request to a flag's endpoint.
     *
     * @param method GET or PUT
     * @param flag the flag's name
     * @param body the JSON body, or {@code null}
     * @return the status
     * @throws IOException when the service cannot be reached
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private int send(String method, String flag, String body) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/flags/" + flag)).timeout(Duration.ofSeconds(5));
        request = body == null ? request.GET() : request.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body));
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        }
    }
}
