package io.github.fastformer.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Policy violations have stable identities; accepting one old edge never accepts another edge. */
final class ArchitectureRules {
    static final String ROOT = "io.github.fastformer.";
    private static final Set<String> LAYERS = Set.of("core", "common", "protocol", "client", "server");
    private static final List<String> RETIRED_SUFFIXES = List.of("Manager", "Dispatcher", "Core", "Owner", "Semantics");
    // Manual section 10 explicitly names this bounded face-algorithm entry after section 1.3 bans generic dispatchers.
    private static final Set<String> SPECIFIED_PUBLIC_ENTRIES = Set.of(
            ROOT + "common.geometry.raster.face.FaceRasterDispatcher");

    record Findings(Set<String> debt, Set<String> strict, Map<String, Set<String>> clientPackages) {}

    private ArchitectureRules() {}

    static Findings inspect(Map<String, Set<String>> classes, Path sources) {
        Set<String> debt = new TreeSet<>();
        Set<String> strict = new TreeSet<>();
        Map<String, Set<String>> packages = new TreeMap<>();
        classes.forEach((owner, targets) -> {
            if (!owner.startsWith(ROOT)) return;
            packages.computeIfAbsent(PackageGraph.packageName(owner), ignored -> new TreeSet<>()).add(owner);
            inspectType(owner, debt);
            inspectSource(owner, sources, debt);
            for (String target : targets) {
                String edge = owner + " -> " + target;
                if (forbiddenLayer(owner, target)) debt.add("layer|" + edge);
                if (strictBoundary(owner, target)) strict.add("boundary|" + edge);
            }
        });
        inspectPackages(packages, sources, debt);
        Map<String, Set<String>> client = PackageGraph.fromClasses(classes, ROOT + "client.");
        for (Set<String> component : PackageGraph.components(client)) {
            if (component.size() <= 1) continue;
            for (String origin : component) for (String target : client.getOrDefault(origin, Set.of())) {
                if (component.contains(target)) debt.add("client-cycle|" + origin + " -> " + target);
            }
        }
        return new Findings(debt, strict, client);
    }

    static boolean forbiddenLayer(String owner, String target) {
        String origin = layer(owner);
        if (origin.equals("core") && (target.startsWith("net.minecraft.") || target.startsWith("net.neoforged."))) return true;
        if ((origin.equals("common") || origin.equals("protocol"))
                && (target.startsWith("net.minecraft.client.") || target.startsWith("net.minecraft.server."))) return true;
        if (!target.startsWith(ROOT) || !LAYERS.contains(origin)) return false;
        String destination = layer(target);
        return switch (origin) {
            case "core" -> !destination.equals("core");
            case "common" -> !Set.of("common", "core").contains(destination);
            case "protocol" -> !Set.of("protocol", "common", "core").contains(destination);
            case "client" -> !Set.of("client", "protocol", "common", "core").contains(destination);
            case "server" -> !Set.of("server", "protocol", "common", "core").contains(destination);
            default -> false;
        };
    }

    static boolean strictBoundary(String owner, String target) {
        if (owner.startsWith(ROOT + "client.")
                && !owner.equals(ROOT + "client.network.outbound.ServerGateway")
                && (target.equals("net.neoforged.neoforge.network.PacketDistributor")
                    || target.equals("net.neoforged.neoforge.network.registration.NetworkRegistry"))) return true;
        if (owner.startsWith(ROOT + "client.input.")) return target.startsWith(ROOT + "client.render.");
        if (owner.startsWith(ROOT + "client.interaction.selectiongesture.")
                || owner.startsWith(ROOT + "client.interaction.drag.")) {
            return target.startsWith(ROOT + "client.input.") || target.startsWith(ROOT + "client.render.")
                    || target.startsWith(ROOT + "client.session.")
                    || target.startsWith(ROOT + "client.operation.controller.")
                    || target.equals("net.minecraft.client.Minecraft");
        }
        if (owner.startsWith(ROOT + "client.preview.hud.") || owner.equals(ROOT + "client.render.hud.HudFrameRenderer")
                || Set.of(ROOT + "client.preview.selection.OperationPreviewRenderer",
                    ROOT + "client.preview.selection.SmartSelectionRenderer",
                    ROOT + "client.preview.selection.SmartSelectionIsolationRenderer").contains(owner)) {
            return target.startsWith(ROOT + "client.input.") || target.startsWith(ROOT + "client.session.")
                    || target.startsWith(ROOT + "client.operation.") || target.startsWith(ROOT + "client.placement.")
                    || target.startsWith(ROOT + "client.geometry.pointer.")
                    || target.startsWith(ROOT + "client.render.core.")
                    || target.equals("net.minecraft.client.Minecraft");
        }
        return owner.startsWith(ROOT + "client.feedback.")
                && (target.startsWith(ROOT + "client.render.") || target.startsWith(ROOT + "client.input.")
                    || target.startsWith(ROOT + "client.session."));
    }

    private static void inspectType(String owner, Set<String> debt) {
        String layer = layer(owner);
        if (!LAYERS.contains(layer) && !owner.equals(ROOT + "FastFormer")) debt.add("unmigrated-type|" + owner);
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        if (!SPECIFIED_PUBLIC_ENTRIES.contains(owner) && RETIRED_SUFFIXES.stream().anyMatch(simple::endsWith)) {
            debt.add("retired-name|" + owner);
        }
    }

    private static void inspectSource(String owner, Path sources, Set<String> debt) {
        Path source = sources.resolve(owner.replace('.', '/') + ".java");
        if (!Files.isRegularFile(source)) return; // A package-private sibling uses its declaring source file.
        try {
            List<String> lines = Files.readAllLines(source);
            boolean smallEntry = owner.endsWith(".FastPlaceClientInput") || owner.endsWith(".FastPlaceClientPreviewCore");
            if (smallEntry && lines.size() > 200) debt.add("oversize-entry|" + owner);
            if (lines.size() > 800 && lines.stream().noneMatch(line -> line.contains("@architecture-large-algorithm"))) {
                debt.add("oversize-source|" + owner);
            }
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException("Cannot inspect source length: " + source, failure);
        }
    }

    private static void inspectPackages(Map<String, Set<String>> packages, Path sources, Set<String> debt) {
        packages.forEach((name, types) -> {
            if (name.equals("io.github.fastformer")) return;
            Path directory = sources.resolve(name.replace('.', '/'));
            if (!Files.isRegularFile(directory.resolve("package-info.java"))) debt.add("package-description|" + name);
            // Record each surplus type so renaming/moving one cannot silently permit a different addition.
            if (types.size() > 12) types.forEach(type -> debt.add("oversize-package|" + name + "|" + type));
            if (types.size() == 1 && !isDocumentedApi(directory)) debt.add("singleton-package|" + name);
        });
    }

    private static boolean isDocumentedApi(Path directory) {
        Path description = directory.resolve("package-info.java");
        try {
            return Files.isRegularFile(description) && Files.readString(description).contains("@architecture-api-entry");
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException("Cannot read package description: " + description, failure);
        }
    }

    private static String layer(String name) {
        if (!name.startsWith(ROOT)) return "external";
        String rest = name.substring(ROOT.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? "entry" : rest.substring(0, dot);
    }
}
