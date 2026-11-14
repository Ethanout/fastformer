package io.github.fastformer.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Writes deterministic review artifacts without hiding outstanding migration debt. */
final class ArchitectureReport {
    private ArchitectureReport() {}

    static Set<String> readBaseline(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Missing explicit architecture baseline: " + path);
        Set<String> baseline = new TreeSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            if (!baseline.add(line)) throw new IOException("Duplicate baseline entry: " + line);
            if (line.contains("*")) throw new IOException("Wildcards are forbidden in architecture debt: " + line);
        }
        return baseline;
    }

    static void writeBaseline(Path path, Set<String> debt) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, "# Exact existing migration debt. This file is not a completion waiver.\n"
                + "# Refresh explicitly only after reviewing architecture.json; never refresh from check.\n"
                + String.join("\n", debt) + "\n", StandardCharsets.UTF_8);
    }

    static void write(Path directory, Map<String, Set<String>> dependencies, ArchitectureRules.Findings findings,
            Set<String> baseline) throws IOException {
        Files.createDirectories(directory);
        Set<String> added = difference(findings.debt(), baseline);
        Set<String> resolved = difference(baseline, findings.debt());
        Map<String, Long> categories = categories(findings.debt());
        String json = "{\n  \"debtCount\": " + findings.debt().size()
                + ",\n  \"categories\": " + categories.entrySet().stream()
                    .map(entry -> quote(entry.getKey()) + ":" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"))
                + ",\n  \"strictBoundaryViolations\": " + array(findings.strict())
                + ",\n  \"newDebt\": " + array(added) + ",\n  \"resolvedDebt\": " + array(resolved)
                + ",\n  \"remainingDebt\": " + array(findings.debt())
                + ",\n  \"clientPackageGraph\": " + graph(findings.clientPackages())
                + ",\n  \"classDependencies\": " + graph(dependencies) + "\n}\n";
        Files.writeString(directory.resolve("architecture.json"), json, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("summary.txt"),
                "Compiled top-level types: " + dependencies.size() + "\nOutstanding migration debt: " + findings.debt().size()
                + "\nNew debt: " + added.size() + "\nResolved debt: " + resolved.size()
                + "\nStrict boundary violations: " + findings.strict().size()
                + "\nDebt categories: " + categories
                + "\nFull architectural acceptance: " + (findings.debt().isEmpty() && findings.strict().isEmpty() ? "PASS" : "NOT ACHIEVED") + "\n",
                StandardCharsets.UTF_8);
    }

    static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static Map<String, Long> categories(Set<String> debt) {
        Map<String, Long> result = new TreeMap<>();
        debt.forEach(item -> result.merge(item.substring(0, item.indexOf('|')), 1L, Long::sum));
        return result;
    }

    private static String graph(Map<String, Set<String>> graph) {
        return "{" + graph.entrySet().stream().map(entry -> quote(entry.getKey()) + ":" + array(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(",\n")) + "}";
    }

    private static String array(Collection<String> values) {
        return values.stream().map(ArchitectureReport::quote).collect(java.util.stream.Collectors.joining(",\n", "[", "]"));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
