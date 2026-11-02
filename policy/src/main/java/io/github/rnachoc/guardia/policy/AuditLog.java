package io.github.rnachoc.guardia.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An append-only, hash-chained audit log of every proposal, decision and action. Each line is a JSON entry
 * holding its sequence number, when it was written, the previous entry's hash, its kind, its data and its own
 * hash. Editing,
 * deleting, inserting or reordering any entry breaks the chain, which {@link #verify()} reports.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
public class AuditLog {

    /** The hash the first entry points back to. */
    public static final String GENESIS = "0".repeat(64);

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Path file;
    private final Clock clock;

    /**
     * Opens, or will create, the log at a path.
     *
     * @param file the JSON Lines file
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public AuditLog(Path file) {
        this(file, Clock.systemUTC());
    }

    /**
     * Opens, or will create, the log at a path, timing entries with a given clock.
     *
     * @param file the JSON Lines file
     * @param clock what tells the time of each entry
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public AuditLog(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /**
     * Appends one entry, chained to the last one in the file.
     *
     * @param kind what happened, such as proposal, decision or action
     * @param data its details
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public synchronized void append(String kind, Map<String, ?> data) {
        try {
            List<String> lines = Files.exists(file) ? Files.readAllLines(file) : List.of();
            String previous = lines.isEmpty() ? GENESIS : JSON.readTree(lines.getLast()).path("hash").asText();
            String line = entry(lines.size() + 1L, previous, kind, data, clock.instant().truncatedTo(ChronoUnit.SECONDS).toString()) + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Builds one entry's line: its fields in a fixed order, and a hash over all of them.
     *
     * @param sequence its position, from 1
     * @param previous the previous entry's hash
     * @param kind what happened
     * @param data its details
     * @return the JSON line, without a line break
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public static String entry(long sequence, String previous, String kind, Map<String, ?> data) {
        return entry(sequence, previous, kind, data, null);
    }

    /**
     * Builds one entry's line, with the time it was written inside the hash.
     *
     * @param sequence its position, from 1
     * @param previous the previous entry's hash
     * @param kind what happened
     * @param data its details
     * @param at when it happened, in ISO 8601, or {@code null} to leave it out
     * @return the JSON line, without a line break
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public static String entry(long sequence, String previous, String kind, Map<String, ?> data, String at) {
        Map<String, Object> body = new TreeMap<>(Map.of("sequence", sequence, "previous", previous, "kind", kind, "data", new TreeMap<>(data)));
        if (at != null) {
            body.put("at", at);
        }
        try {
            String unsigned = JSON.writeValueAsString(body);
            body.put("hash", sha256(unsigned));
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Checks the whole chain and returns every problem found, or an empty list.
     *
     * @return the problems, each naming the entry where the chain breaks
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public List<String> verify() {
        List<String> problems = new ArrayList<>();
        try {
            List<String> lines = Files.exists(file) ? Files.readAllLines(file) : List.of();
            String previous = GENESIS;
            for (int i = 0; i < lines.size(); i++) {
                JsonNode node = JSON.readTree(lines.get(i));
                Map<String, Object> body = new TreeMap<>(JSON.convertValue(node, new TypeReference<Map<String, Object>>() { }));
                Object claimed = body.remove("hash");
                String recomputed = sha256(JSON.writeValueAsString(body));
                if (!recomputed.equals(claimed)) {
                    problems.add("entry " + (i + 1) + " does not match its own hash");
                }
                if (!previous.equals(node.path("previous").asText())) {
                    problems.add("entry " + (i + 1) + " does not point at the entry before it");
                }
                if (node.path("sequence").asLong() != i + 1L) {
                    problems.add("entry " + (i + 1) + " is out of sequence");
                }
                previous = String.valueOf(claimed);
            }
        } catch (IOException unreadable) {
            problems.add("the log cannot be read: " + unreadable.getMessage());
        }
        return problems;
    }

    /**
     * Hashes text with SHA-256.
     *
     * @param text the text
     * @return the lowercase hex digest
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Tells whether any entry of a kind carries a value in its data, such as a claimed idempotency key.
     *
     * @param kind the entry's kind
     * @param field the data field
     * @param value the value to look for
     * @return {@code true} when such an entry exists
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public synchronized boolean contains(String kind, String field, String value) {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            for (String line : Files.readAllLines(file)) {
                JsonNode node = JSON.readTree(line);
                if (kind.equals(node.path("kind").asText()) && value.equals(node.path("data").path(field).asText())) {
                    return true;
                }
            }
            return false;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Reads back the latest entries, oldest first, without their hashes, for the timeline.
     *
     * @param limit how many entries at most
     * @return the entries
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public synchronized List<Map<String, Object>> latest(int limit) {
        try {
            List<String> lines = Files.exists(file) ? Files.readAllLines(file) : List.of();
            List<Map<String, Object>> entries = new ArrayList<>();
            for (String line : lines.subList(Math.max(0, lines.size() - limit), lines.size())) {
                Map<String, Object> entry = JSON.readValue(line, new TypeReference<Map<String, Object>>() { });
                entry.remove("hash");
                entry.remove("previous");
                entries.add(entry);
            }
            return entries;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
