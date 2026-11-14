package io.github.fastformer.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Dependency-free tool tests also run when Minecraft production sources cannot compile. */
public final class ArchitectureToolTests {
    private static final String ROOT = "io/github/fastformer/";

    private ArchitectureToolTests() {}

    public static void main(String[] arguments) throws Exception {
        readsSemanticReferences();
        combinesNestedClassesWithoutInventingSeparateTypes();
        findsOnlyRealCycleEdges();
        reportsEveryInternalCycleEdge();
        checksLayerAndCapabilityBoundaries();
        hudBoundaryReadsSemanticReferences();
        gestureBoundariesReadSemanticReferences();
        clientTransportBoundaryReadsSemanticReferences();
        worldWriteBoundariesKeepOnlySnapshotReplayPrimitives();
        exemptsOnlyTheExplicitFaceRasterEntryName();
        requiresDocumentedReasonForSingletonApiPackages();
        baselineNeverAcceptsUnlistedEdges();
        exercisesCommandFailureAndReport();
        System.out.println("Architecture tool: 13 test groups passed");
    }

    private static void worldWriteBoundariesKeepOnlySnapshotReplayPrimitives() throws Exception {
        String root = "io.github.fastformer.";
        String entry = root + "server.history.entry.Fixture";
        String leases = root + "server.worldwrite.Fixture";
        String block = root + "server.worldwrite.transaction.Fixture";
        Path sources = Files.createTempDirectory("fastformer-world-write-boundary-");
        try {
            for (String snapshot : Set.of("ReversibleBlockSnapshot", "BlockEntitySnapshot")) {
                String target = root + "server.worldwrite.snapshot." + snapshot;
                require(!ArchitectureRules.strictBoundary(entry, target), "Replay entry must accept its snapshot primitive");
            }
            for (String capability : Set.of("transaction.WorldChangeTransaction", "transaction.WorldOperationCommit",
                    "transaction.BlockEditTransaction", "WorldWriteLeases", "snapshot.ReversibleBlockSnapshotExtra")) {
                String target = root + "server.worldwrite." + capability;
                Map<String, Set<String>> references = new TreeMap<>();
                BytecodeDependencies.read(fixture(entry.replace('.', '/'), target.replace('.', '/')), references);
                require(ArchitectureRules.inspect(references, sources).strict().contains("boundary|" + entry + " -> " + target),
                        "History entry must not acquire transaction, lease, commit, or similarly named capabilities");
            }
            for (String target : Set.of(root + "server.worldwrite.snapshot.ReversibleBlockSnapshot",
                    root + "server.recovery.JournalPreparation", root + "server.history.HistoryService")) {
                require(ArchitectureRules.strictBoundary(leases, target), "Lease capability must remain independent of block and journal orchestration");
            }
            require(!ArchitectureRules.strictBoundary(block, root + "server.recovery.JournalPreparation"),
                    "Transactional block writers own their journal boundary");
            require(ArchitectureRules.strictBoundary(block, root + "server.session.SessionSystem"),
                    "Transactional block writers must not call feature assembly");
            for (String part : Set.of("snapshot", "transaction", "effect")) {
                require(ArchitectureRules.strictBoundary(root + "server.worldwrite." + part + ".Fixture",
                        root + "server.session.SessionSystem"), "Every world-write primitive must reject feature assembly");
            }
        } finally { deleteFixture(sources); }
    }

    private static void readsSemanticReferences() {
        ClassWriter type = new ClassWriter(0);
        type.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, ROOT + "client/Fixture",
                "Ljava/lang/Object;Lio/github/fastformer/common/Generic<Lio/github/fastformer/common/Argument;>;",
                "java/lang/Object", new String[]{ROOT + "common/Contract"});
        type.visitAnnotation("L" + ROOT + "common/Marker;", false).visit("value", Type.getObjectType(ROOT + "common/Annotated"));
        type.visitField(Opcodes.ACC_PRIVATE, "target", "L" + ROOT + "server/Field;", null, null).visitEnd();
        var method = type.visitMethod(Opcodes.ACC_PUBLIC, "call", "(L" + ROOT + "common/Parameter;)L"
                + ROOT + "common/Result;", null, new String[]{ROOT + "common/Failure"});
        method.visitCode();
        method.visitTypeInsn(Opcodes.CHECKCAST, ROOT + "server/Cast");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, ROOT + "server/Call", "apply", "()V", false);
        method.visitLdcInsn(Type.getObjectType(ROOT + "common/ClassLiteral"));
        method.visitLdcInsn("io.github.fastformer.server.NotASymbolicReference");
        method.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false),
                Type.getMethodType("()V"), new Handle(Opcodes.H_INVOKESTATIC, ROOT + "server/Lambda", "apply", "()V", false),
                Type.getMethodType("()V"));
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, 2);
        method.visitEnd();
        type.visitEnd();
        Map<String, Set<String>> references = new TreeMap<>();
        BytecodeDependencies.read(type.toByteArray(), references);
        Set<String> actual = references.get("io.github.fastformer.client.Fixture");
        for (String expected : Set.of("common.Generic", "common.Argument", "common.Contract", "common.Marker",
                "common.Annotated", "server.Field", "common.Parameter", "common.Result", "common.Failure",
                "server.Cast", "server.Call", "common.ClassLiteral", "server.Lambda")) {
            require(actual.contains("io.github.fastformer." + expected), "Missing semantic reference: " + expected);
        }
        require(!actual.contains("io.github.fastformer.server.NotASymbolicReference"), "Plain text must not create dependencies");
    }

    private static void combinesNestedClassesWithoutInventingSeparateTypes() {
        Map<String, Set<String>> result = new TreeMap<>();
        BytecodeDependencies.read(fixture(ROOT + "client/Owner", ROOT + "common/First"), result);
        BytecodeDependencies.read(fixture(ROOT + "client/Owner$Nested", ROOT + "server/Hidden$Inner"), result);
        require(result.size() == 1, "Nested types count as their source-level owner");
        require(result.values().iterator().next().containsAll(Set.of("io.github.fastformer.common.First",
                "io.github.fastformer.server.Hidden")), "Nested dependencies must merge into owner");
    }

    private static void findsOnlyRealCycleEdges() {
        var graph = Map.of("a", Set.of("b"), "b", Set.of("a", "c"), "c", Set.of("d"), "d", Set.<String>of());
        var components = PackageGraph.components(graph);
        require(components.contains(Set.of("a", "b")), "Mutual dependency must form one component");
        require(components.contains(Set.of("c")) && components.contains(Set.of("d")), "Outgoing path is not a cycle");
        require(PackageGraph.components(Map.of("a", Set.of("b"), "b", Set.of("c"), "c", Set.of("a")))
                .equals(java.util.List.of(Set.of("a", "b", "c"))), "Cycles longer than two edges must be detected");
    }

    private static void checksLayerAndCapabilityBoundaries() {
        String root = "io.github.fastformer.";
        require(ArchitectureRules.forbiddenLayer(root + "protocol.Packet", root + "fastplace.Model"), "Legacy package is not common");
        require(ArchitectureRules.forbiddenLayer(root + "client.Draw", root + "server.State"), "Client/server dependency");
        require(ArchitectureRules.forbiddenLayer(root + "core.Math", "net.minecraft.core.BlockPos"), "Core/game dependency");
        require(!ArchitectureRules.forbiddenLayer(root + "client.Draw", root + "protocol.Packet"), "Allowed layer dependency");
        require(ArchitectureRules.strictBoundary(root + "client.input.Keys", root + "client.render.Lines"), "Input/render dependency");
        require(ArchitectureRules.strictBoundary(root + "client.feedback.State", root + "client.session.Scope"), "Feedback/assembly dependency");
        require(!ArchitectureRules.strictBoundary(root + "client.feedback.State", root + "common.Model"), "Feedback domain dependency");
        for (String owner : Set.of(root + "client.preview.hud.HudModel", root + "client.render.hud.HudFrameRenderer",
                root + "client.preview.selection.OperationPreviewRenderer", root + "client.preview.selection.SmartSelectionRenderer",
                root + "client.preview.selection.SmartSelectionIsolationRenderer")) {
            for (String target : Set.of(root + "client.input.Keys", root + "client.session.Scope",
                    root + "client.operation.pointer.Resolver", root + "client.geometry.pointer.GeometryPointer",
                    root + "client.render.core.Adapter", "net.minecraft.client.Minecraft")) {
                require(ArchitectureRules.strictBoundary(owner, target), "HUD live-state reference: " + target);
            }
            require(!ArchitectureRules.strictBoundary(owner, root + "client.feedback.FeedbackFrame"), "HUD frozen feedback");
        }
    }

    private static void reportsEveryInternalCycleEdge() throws Exception {
        String root = "io.github.fastformer.client.";
        Path sources = Files.createTempDirectory("fastformer-architecture-cycle-");
        try {
            var references = Map.of(root + "a.First", Set.of(root + "b.Second"),
                    root + "b.Second", Set.of(root + "a.First", root + "c.Last"),
                    root + "c.Last", Set.<String>of());
            var findings = ArchitectureRules.inspect(references, sources);
            Set<String> cycleEdges = new java.util.TreeSet<>();
            for (String debt : findings.debt()) if (debt.startsWith("client-cycle|")) cycleEdges.add(debt);
            require(cycleEdges.equals(Set.of("client-cycle|" + root + "a -> " + root + "b",
                    "client-cycle|" + root + "b -> " + root + "a")), "Report exactly the edges inside a real cycle");
        } finally { deleteFixture(sources); }
    }

    private static void hudBoundaryReadsSemanticReferences() throws Exception {
        ClassWriter type = new ClassWriter(0);
        String owner = ROOT + "client/preview/hud/Fixture";
        String scope = ROOT + "client/session/Scope";
        type.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        type.visitField(Opcodes.ACC_PRIVATE, "aliased", "L" + scope + ";", null, null).visitEnd();
        var method = type.visitMethod(Opcodes.ACC_PUBLIC, "capture", "()Ljava/lang/Runnable;", null, null);
        method.visitCode();
        method.visitLdcInsn(Type.getObjectType(ROOT + "client/geometry/pointer/GeometryPointer"));
        method.visitInsn(Opcodes.POP);
        method.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false),
                Type.getMethodType("()V"), new Handle(Opcodes.H_INVOKESTATIC, scope, "anyName", "()V", false),
                Type.getMethodType("()V"));
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        type.visitEnd();
        Map<String, Set<String>> references = new TreeMap<>();
        BytecodeDependencies.read(type.toByteArray(), references);
        Path sources = Files.createTempDirectory("fastformer-hud-boundary-");
        try {
            var findings = ArchitectureRules.inspect(references, sources);
            require(findings.strict().contains("boundary|" + owner.replace('/', '.') + " -> " + scope.replace('/', '.')),
                    "HUD field or method-reference alias bypassed boundary");
            require(findings.strict().stream().anyMatch(edge -> edge.endsWith("client.geometry.pointer.GeometryPointer")),
                    "HUD class literal bypassed boundary");
        } finally { deleteFixture(sources); }
    }

    private static void gestureBoundariesReadSemanticReferences() throws Exception {
        String root = "io.github.fastformer.";
        Path sources = Files.createTempDirectory("fastformer-gesture-boundary-");
        try {
            for (String packageName : Set.of("client.interaction.selectiongesture.", "client.interaction.drag.")) {
                String owner = ROOT + packageName.replace('.', '/') + "Fixture";
                String input = ROOT + "client/input/Keys";
                String render = ROOT + "client/render/Lines";
                String session = ROOT + "client/session/Scope";
                String controller = ROOT + "client/operation/controller/OperationContext";
                ClassWriter type = new ClassWriter(0);
                type.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
                type.visitField(Opcodes.ACC_PRIVATE, "aliased", "L" + input + ";", null, null).visitEnd();
                var method = type.visitMethod(Opcodes.ACC_PUBLIC, "capture", "(L" + render + ";)Ljava/lang/Runnable;", null, null);
                method.visitCode();
                method.visitLdcInsn(Type.getObjectType(session));
                method.visitInsn(Opcodes.POP);
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "getInstance",
                        "()Lnet/minecraft/client/Minecraft;", false);
                method.visitInsn(Opcodes.POP);
                method.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
                        new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false),
                        Type.getMethodType("()V"), new Handle(Opcodes.H_INVOKESTATIC, controller, "renamedAlias", "()V", false),
                        Type.getMethodType("()V"));
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(2, 2);
                method.visitEnd();
                type.visitEnd();
                Map<String, Set<String>> references = new TreeMap<>();
                BytecodeDependencies.read(type.toByteArray(), references);
                Set<String> expected = new java.util.TreeSet<>();
                for (String target : Set.of(input, render, session, controller, "net/minecraft/client/Minecraft")) {
                    expected.add("boundary|" + owner.replace('/', '.') + " -> " + target.replace('/', '.'));
                }
                require(ArchitectureRules.inspect(references, sources).strict().equals(expected),
                        "Gesture capability missed a field, signature, class literal, global query, or method-reference alias");
                for (String allowed : Set.of("client.operation.input.SelectionPointerPress",
                        "client.operation.workspace.ClientOperationWorkspace", "client.operation.model.AabbAdjustDecision",
                        "client.interaction.selectiongesture.SelectionGestureTransforms",
                        "client.interaction.drag.DragAxisFrame", "common.geometry.gizmo.AxisGizmo", "workspace.model.ClientSelectionPart")) {
                    require(!ArchitectureRules.strictBoundary(root + packageName + "Fixture", root + allowed),
                            "Gesture capability rejected explicit data or typed port: " + allowed);
                }
            }
        } finally { deleteFixture(sources); }
    }

    private static void clientTransportBoundaryReadsSemanticReferences() throws Exception {
        Path sources = Files.createTempDirectory("fastformer-client-transport-boundary-");
        try {
            for (String target : Set.of("net/neoforged/neoforge/network/PacketDistributor",
                    "net/neoforged/neoforge/network/registration/NetworkRegistry")) {
                for (String reference : Set.of("field", "signature", "constant", "method-reference")) {
                    for (String owner : Set.of("client/input/Fixture", "client/feedback/Fixture",
                            "client/interaction/selectiongesture/Fixture", "client/interaction/drag/Fixture",
                            "client/preview/hud/Fixture", "client/render/hud/HudFrameRenderer",
                            "client/operation/Fixture", "client/session/Fixture",
                            "client/network/outbound/OtherGateway", "client/network/outbound/ServerGatewayAlias",
                            "client/network/other/ServerGateway")) {
                        Map<String, Set<String>> references = new TreeMap<>();
                        BytecodeDependencies.read(clientTransportFixture(ROOT + owner, target, reference), references);
                        require(ArchitectureRules.inspect(references, sources).strict().equals(Set.of(
                                        "boundary|" + (ROOT + owner).replace('/', '.') + " -> " + target.replace('/', '.'))),
                                "Client transport boundary missed " + reference + " from " + owner + " to " + target);
                    }
                    for (String owner : Set.of("client/network/outbound/ServerGateway",
                            "server/network/Fixture", "common/network/Fixture")) {
                        Map<String, Set<String>> references = new TreeMap<>();
                        BytecodeDependencies.read(clientTransportFixture(ROOT + owner, target, reference), references);
                        require(ArchitectureRules.inspect(references, sources).strict().isEmpty(),
                                "Client transport boundary rejected exact gateway or nonclient owner: " + owner);
                    }
                }
            }
        } finally { deleteFixture(sources); }
    }

    private static byte[] clientTransportFixture(String owner, String target, String reference) {
        ClassWriter type = new ClassWriter(0);
        type.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        if (reference.equals("field")) {
            type.visitField(Opcodes.ACC_PRIVATE, "aliased", "L" + target + ";", null, null).visitEnd();
        } else if (reference.equals("signature")) {
            type.visitField(Opcodes.ACC_PRIVATE, "aliased", "Ljava/util/List;",
                    "Ljava/util/List<L" + target + ";>;", null).visitEnd();
        } else {
            var method = type.visitMethod(Opcodes.ACC_PUBLIC, "capture", "()Ljava/lang/Object;", null, null);
            method.visitCode();
            switch (reference) {
                case "constant" -> method.visitLdcInsn(Type.getObjectType(target));
                case "method-reference" -> method.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
                        new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false),
                        Type.getMethodType("()V"), new Handle(Opcodes.H_INVOKESTATIC, target, "renamedAlias", "()V", false),
                        Type.getMethodType("()V"));
                default -> throw new IllegalArgumentException("Unknown transport reference: " + reference);
            }
            method.visitInsn(Opcodes.ARETURN);
            method.visitMaxs(2, 1);
            method.visitEnd();
        }
        type.visitEnd();
        return type.toByteArray();
    }

    private static void exemptsOnlyTheExplicitFaceRasterEntryName() throws Exception {
        String root = "io.github.fastformer.";
        Path sources = Files.createTempDirectory("fastformer-architecture-entry-name-");
        try {
            String namedEntry = root + "common.geometry.raster.face.FaceRasterDispatcher";
            String adjacentEntry = root + "common.geometry.raster.face.OtherDispatcher";
            String sameNameElsewhere = root + "common.other.FaceRasterDispatcher";
            var findings = ArchitectureRules.inspect(Map.of(namedEntry, Set.of(), adjacentEntry, Set.of(),
                    sameNameElsewhere, Set.of()), sources);
            require(!findings.debt().contains("retired-name|" + namedEntry), "Section 10 face entry must retain its specified name");
            require(findings.debt().contains("retired-name|" + adjacentEntry), "Nearby dispatcher names are not exempt");
            require(findings.debt().contains("retired-name|" + sameNameElsewhere), "Same simple name in another package is not exempt");
        } finally { deleteFixture(sources); }
    }

    private static void requiresDocumentedReasonForSingletonApiPackages() throws Exception {
        Path sources = Files.createTempDirectory("fastformer-architecture-singleton-");
        String documented = "io.github.fastformer.common.raster";
        String undocumented = "io.github.fastformer.common.misc";
        try {
            Path api = Files.createDirectories(sources.resolve(documented.replace('.', '/')));
            Path plain = Files.createDirectories(sources.resolve(undocumented.replace('.', '/')));
            Files.writeString(api.resolve("package-info.java"), "/** @architecture-api-entry Observer is a shared progress contract. */");
            Files.writeString(plain.resolve("package-info.java"), "/** One helper. */");
            var findings = ArchitectureRules.inspect(Map.of(documented + ".Observer", Set.of(),
                    undocumented + ".Helper", Set.of()), sources);
            require(!findings.debt().contains("singleton-package|" + documented), "Documented public API package is allowed");
            require(findings.debt().contains("singleton-package|" + undocumented), "Ordinary singleton remains debt");
        } finally { deleteFixture(sources); }
    }

    private static void baselineNeverAcceptsUnlistedEdges() throws Exception {
        Path root = Files.createTempDirectory("fastformer-architecture-baseline-");
        Path baseline = root.resolve("baseline.txt");
        try {
            ArchitectureReport.writeBaseline(baseline, Set.of("layer|a -> b"));
            Set<String> allowed = ArchitectureReport.readBaseline(baseline);
            require(ArchitectureReport.difference(Set.of("layer|a -> b", "layer|a -> c"), allowed)
                    .equals(Set.of("layer|a -> c")), "Exact baseline must reject new target");
            Files.writeString(baseline, "layer|a -> *\n");
            expectFailure(() -> ArchitectureReport.readBaseline(baseline), "Wildcard debt baseline accepted");
            Files.writeString(baseline, "same\nsame\n");
            expectFailure(() -> ArchitectureReport.readBaseline(baseline), "Duplicate debt baseline accepted");
        } finally { deleteFixture(root); }
    }

    private static void exercisesCommandFailureAndReport() throws Exception {
        Path root = Files.createTempDirectory("fastformer-architecture-cli-");
        try {
            Path classes = Files.createDirectory(root.resolve("classes"));
            Path sources = Files.createDirectory(root.resolve("sources"));
            Path baseline = root.resolve("baseline.txt");
            Path report = root.resolve("report");
            Files.write(classes.resolve("Fixture.class"), fixture(ROOT + "client/sample/Fixture", ROOT + "fastplace/Old"));
            ArchitectureCheck.main(args("baseline", sources, baseline, report, classes));
            String original = Files.readString(baseline);
            ArchitectureCheck.main(args("verify", sources, baseline, report, classes));
            require(Files.readString(baseline).equals(original), "Verification must not rewrite baseline");
            expectFailure(() -> ArchitectureCheck.main(args("strict", sources, baseline, report, classes)), "Strict mode accepted old debt");
            require(Files.readString(report.resolve("summary.txt")).contains("NOT ACHIEVED"), "Debt report must be explicit");
            Files.write(classes.resolve("Fixture.class"), fixture(ROOT + "client/sample/Fixture", ROOT + "server/NewDependency"));
            expectFailure(() -> ArchitectureCheck.main(args("verify", sources, baseline, report, classes)), "New class edge accepted");
            Files.write(classes.resolve("Fixture.class"), fixture(ROOT + "client/input/Fixture", ROOT + "client/render/Hidden"));
            expectFailure(() -> ArchitectureCheck.main(args("baseline", sources, baseline, report, classes)), "Strict boundary baseline accepted");
        } finally { deleteFixture(root); }
    }

    private static String[] args(String mode, Path sources, Path baseline, Path report, Path classes) {
        return new String[]{mode, sources.toString(), baseline.toString(), report.toString(), classes.toString()};
    }

    private static byte[] fixture(String owner, String target) {
        ClassWriter type = new ClassWriter(0);
        type.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        type.visitField(Opcodes.ACC_PUBLIC, "value", "L" + target + ";", null, null).visitEnd();
        type.visitEnd();
        return type.toByteArray();
    }

    private static void expectFailure(CheckedAction action, String message) throws Exception {
        try { action.run(); } catch (IllegalStateException | java.io.IOException expected) { return; }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void deleteFixture(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
