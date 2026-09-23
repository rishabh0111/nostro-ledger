package io.nostro.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Every response, counted by arm, status and Problem type. Gatling's report gives throughput and
 * percentiles; this gives what the responses were, which is the half of the claim a latency chart
 * cannot show.
 */
final class Outcomes {

    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

    private Outcomes() {
    }

    static void count(String arm, int status, String type) {
        var key = arm + "\t" + status + "\t" + (type == null ? "-" : type.substring(type.lastIndexOf('/') + 1));
        COUNTS.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    static void report(int users, Duration arm) {
        var lines = new ArrayList<String>();
        lines.add("# " + users + " concurrent users per arm, " + arm.toSeconds() + " s each");
        lines.add("arm\tstatus\ttype\tcount");
        new TreeMap<>(COUNTS).forEach((key, count) -> lines.add(key + "\t" + count.sum()));
        lines.forEach(System.out::println);
        write(lines);
    }

    private static void write(List<String> lines) {
        try {
            // Relative to wherever Gatling runs, unless run.sh says where.
            var file = Path.of(System.getProperty("outcomes", "target/load-results/outcomes.tsv"));
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
