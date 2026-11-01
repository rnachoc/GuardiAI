package io.github.rnachoc.guardia.policy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The versioned catalog of actions. Nothing outside it can ever run. It is read strictly: an unknown field
 * in the file is an error, not something silently ignored.
 *
 * @param version the catalog's version
 * @param approval how many people approve an action of each risk tier
 * @param actions every allowed action
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record ActionCatalog(int version, Map<String, String> approval, List<Action> actions) {

    /**
     * Loads a catalog from the classpath.
     *
     * @param resource the file's name on the classpath
     * @return the catalog
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public static ActionCatalog load(String resource) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try (InputStream in = ActionCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no catalog named " + resource);
            }
            return yaml.readValue(in, ActionCatalog.class);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * Finds an action by its exact name.
     *
     * @param name the action's name
     * @return the action, or empty when the catalog has none by that name
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Optional<Action> find(String name) {
        return actions.stream().filter(action -> action.name().equals(name)).findFirst();
    }

    /**
     * One allowed action.
     *
     * @param name the action's name
     * @param tier its risk tier: low, medium or high
     * @param targets the services it may act on
     * @param preconditions what must hold before it runs
     * @param maxPerHour how many times it may run in an hour
     * @param maxBlastRadius how many services it may affect, its target included
     * @param verification the query that shows the system recovered, and how long to wait
     * @param autoRevert what undoes it when recovery is not confirmed
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record Action(String name, String tier, List<String> targets, List<String> preconditions, int maxPerHour,
                         int maxBlastRadius, Verification verification, String autoRevert) {
    }

    /**
     * How an action's effect is verified.
     *
     * @param query the PromQL query, with $target for the service
     * @param expect the condition the result must meet
     * @param timeoutSeconds how long to wait for it
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public record Verification(String query, String expect, int timeoutSeconds) {
    }
}
