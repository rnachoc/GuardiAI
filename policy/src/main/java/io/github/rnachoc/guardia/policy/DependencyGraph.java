package io.github.rnachoc.guardia.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who calls whom in Mercadillo, used to compute an action's blast radius.
 *
 * @param calls for each service, the services it calls
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public record DependencyGraph(Map<String, List<String>> calls) {

    /**
     * Loads the graph from the classpath.
     *
     * @param resource the file's name on the classpath
     * @return the graph
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public static DependencyGraph load(String resource) {
        try (InputStream in = DependencyGraph.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no dependency graph named " + resource);
            }
            return new ObjectMapper(new YAMLFactory()).readValue(in, DependencyGraph.class);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * Returns the services an action on a target can affect: the target and every service that depends
     * on it, directly or through others.
     *
     * @param target the service acted on
     * @return the affected services, the target first
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public Set<String> blastRadius(String target) {
        Set<String> affected = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(target));
        while (!queue.isEmpty()) {
            String service = queue.poll();
            if (affected.add(service)) {
                calls.forEach((caller, callees) -> {
                    if (callees.contains(service)) {
                        queue.add(caller);
                    }
                });
            }
        }
        return affected;
    }
}
