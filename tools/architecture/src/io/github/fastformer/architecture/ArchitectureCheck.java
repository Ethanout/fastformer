package io.github.fastformer.architecture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Standalone verification entry; never loads mod classes or starts Minecraft. */
public final class ArchitectureCheck {
    private ArchitectureCheck() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 5) throw new IllegalArgumentException(
                "Usage: <verify|strict|report|baseline> <source-root> <baseline> <report-directory> <class-directory>...");
        String mode = arguments[0];
        if (!Set.of("verify", "strict", "report", "baseline").contains(mode)) {
            throw new IllegalArgumentException("Unknown architecture mode: " + mode);
        }
        Path sources = Path.of(arguments[1]);
        Path baselinePath = Path.of(arguments[2]);
        List<Path> classes = new ArrayList<>();
        for (int index = 4; index < arguments.length; index++) classes.add(Path.of(arguments[index]));
        var dependencies = BytecodeDependencies.read(classes);
        var findings = ArchitectureRules.inspect(dependencies, sources);
        Set<String> baseline = mode.equals("baseline") || mode.equals("report") && !java.nio.file.Files.exists(baselinePath)
                ? Set.of() : ArchitectureReport.readBaseline(baselinePath);
        ArchitectureReport.write(Path.of(arguments[3]), dependencies, findings, baseline);
        if (mode.equals("report")) return;
        Set<String> failures = new TreeSet<>(findings.strict());
        if (mode.equals("strict")) failures.addAll(findings.debt());
        else if (mode.equals("verify")) failures.addAll(ArchitectureReport.difference(findings.debt(), baseline));
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Architecture check failed (" + failures.size()
                    + " findings; see full report):\n" + String.join("\n", failures.stream().limit(30).toList()));
        }
        if (mode.equals("baseline")) ArchitectureReport.writeBaseline(baselinePath, findings.debt());
        System.out.println("Architecture " + mode + " passed; outstanding migration debt: " + findings.debt().size());
    }
}
