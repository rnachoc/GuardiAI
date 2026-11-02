package io.github.rnachoc.guardia.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Written before the audit log: every proposal, decision and action is appended, each entry is chained to
 * the one before by a hash, and any edit, deletion, insertion or reordering is detected.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
class AuditLogTest {

    @TempDir
    Path folder;

    /**
     * Writes a log with three entries and returns its path.
     *
     * @return the path
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    private Path threeEntries() {
        Path path = folder.resolve("audit.jsonl");
        AuditLog log = new AuditLog(path);
        log.append("proposal", Map.of("id", "p-1", "action", "restart_service", "target", "inventory"));
        log.append("decision", Map.of("proposal", "p-1", "verdict", "NEEDS_APPROVAL"));
        log.append("approval", Map.of("proposal", "p-1", "by", "on-call"));
        return path;
    }

    /**
     * An untouched log verifies, and each entry names the hash of the one before it.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void anUntouchedLogVerifies() throws IOException {
        Path path = threeEntries();
        assertThat(new AuditLog(path).verify()).isEmpty();
        assertThat(Files.readAllLines(path)).hasSize(3);
    }

    /**
     * Appending after reopening the file continues the same chain.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void reopeningContinuesTheChain() {
        Path path = threeEntries();
        new AuditLog(path).append("action", Map.of("proposal", "p-1", "result", "verified"));
        assertThat(new AuditLog(path).verify()).isEmpty();
    }

    /**
     * Changing one character of an entry breaks the chain at that entry.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void detectsAnEditedEntry() throws IOException {
        Path path = threeEntries();
        List<String> lines = new ArrayList<>(Files.readAllLines(path));
        lines.set(1, lines.get(1).replace("NEEDS_APPROVAL", "APPROVED"));
        Files.write(path, lines);
        assertThat(new AuditLog(path).verify()).anyMatch(problem -> problem.contains("entry 2"));
    }

    /**
     * Deleting an entry, or swapping two, breaks the chain.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void detectsADeletedOrReorderedEntry() throws IOException {
        Path path = threeEntries();
        List<String> lines = new ArrayList<>(Files.readAllLines(path));
        Files.write(path, List.of(lines.get(0), lines.get(2)));
        assertThat(new AuditLog(path).verify()).isNotEmpty();

        Path other = folder.resolve("other.jsonl");
        Files.write(other, lines);
        List<String> swapped = new ArrayList<>(lines);
        Collections.swap(swapped, 1, 2);
        Files.write(other, swapped);
        assertThat(new AuditLog(other).verify()).isNotEmpty();
    }

    /**
     * Rewriting an entry and recomputing its own hash still fails, because the next entry points at the old one.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void detectsARewrittenEntryWithARecomputedHash() throws IOException {
        Path path = threeEntries();
        List<String> lines = new ArrayList<>(Files.readAllLines(path));
        String forged = AuditLog.entry(1, AuditLog.GENESIS, "proposal",
                Map.of("id", "p-1", "action", "failover", "target", "payments"));
        lines.set(0, forged);
        Files.write(path, lines);
        assertThat(new AuditLog(path).verify()).anyMatch(problem -> problem.contains("entry 2"));
    }

    /**
     * Each entry records when it was written, inside the hash, so the timeline cannot be quietly redated.
     *
     * @throws IOException if the log cannot be read
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void recordsWhenEachEntryWasWritten() throws IOException {
        Path path = folder.resolve("timed.jsonl");
        java.time.Clock clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-02T09:30:00Z"), java.time.ZoneOffset.UTC);
        new AuditLog(path, clock).append("proposal", Map.of("id", "p-1"));
        assertThat(Files.readString(path)).contains("\"at\":\"2026-11-02T09:30:00Z\"");
        assertThat(new AuditLog(path).verify()).isEmpty();
        Files.writeString(path, Files.readString(path).replace("09:30:00Z", "08:30:00Z"));
        assertThat(new AuditLog(path).verify()).anyMatch(problem -> problem.contains("entry 1"));
    }

    /**
     * The latest entries can be read back, oldest first, for the timeline.
     *
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Test
    void readsBackTheLatestEntries() {
        Path path = threeEntries();
        List<Map<String, Object>> latest = new AuditLog(path).latest(2);
        assertThat(latest).extracting(entry -> entry.get("kind")).containsExactly("decision", "approval");
        assertThat(latest.get(1).get("data")).isEqualTo(Map.of("proposal", "p-1", "by", "on-call"));
    }
}
