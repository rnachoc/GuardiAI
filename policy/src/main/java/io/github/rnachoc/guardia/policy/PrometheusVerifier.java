package io.github.rnachoc.guardia.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an action's verification query against Prometheus. An empty result counts as zero, since a service
 * that serves nothing reports nothing. A Prometheus that fails or cannot be reached never counts as
 * recovered, so doubt leads to a revert and a person, not to a false success.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class PrometheusVerifier implements Executor.Verifier {

    private static final Pattern CONDITION = Pattern.compile("^\\s*(>=|<=|==|>|<)\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * Creates the verifier.
     *
     * @param baseUrl Prometheus's base URL
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public PrometheusVerifier(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Evaluates the query now and compares its first value with the condition.
     *
     * @param query the PromQL query
     * @param expect the condition, such as {@code > 0}
     * @return {@code true} when Prometheus answered and the value meets the condition
     * @throws IllegalArgumentException when the condition is not an operator followed by a number
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public boolean holds(String query, String expect) {
        Matcher condition = CONDITION.matcher(expect);
        if (!condition.matches()) {
            throw new IllegalArgumentException("'" + expect + "' is not a condition such as > 0");
        }
        double value;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/query?query="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(5)).GET().build();
            JsonNode body = JSON.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (!"success".equals(body.path("status").asText())) {
                return false;
            }
            JsonNode result = body.path("data").path("result");
            value = result.isEmpty() ? 0 : Double.parseDouble(result.get(0).path("value").get(1).asText());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception unreachable) {
            return false;
        }
        double threshold = Double.parseDouble(condition.group(2));
        return switch (condition.group(1)) {
            case ">" -> value > threshold;
            case ">=" -> value >= threshold;
            case "<" -> value < threshold;
            case "<=" -> value <= threshold;
            default -> value == threshold;
        };
    }
}
