package io.github.fastformer.workspace.transform;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.workspace.WorkspaceContentPreparer;
import io.github.fastformer.client.session.ClientOperationDraft;
import io.github.fastformer.client.session.ClientOperationDraftCodec;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.network.codec.OperationWorkspacePlanCodec;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceSymmetry;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.preview.WorkspacePreviewComposer;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SelectionShortcutGameTests {
   private SelectionShortcutGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void selectedPartsTurnAroundTheirSharedCentreAndUndoTogether(GameTestHelper helper) {
      var workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(BlockPos.ZERO), part(new BlockPos(4, 0, 0)), part(new BlockPos(20, 0, 0))));
      workspace.toggleSelected(workspace.parts().getLast().id());
      var original = workspace.parts();
      int history = workspace.undoSize();
      apply(helper, workspace, WorkspaceSymmetry.quarterTurnY());
      helper.assertTrue(resolved(workspace.parts().get(0)).containsKey(new BlockPos(2, 0, -2)), "first part did not orbit the shared centre");
      helper.assertTrue(resolved(workspace.parts().get(1)).containsKey(new BlockPos(2, 0, 2)), "second part did not orbit the shared centre");
      helper.assertTrue(workspace.parts().get(2).equals(original.get(2)), "unselected part changed");
      helper.assertTrue(workspace.undoSize() == history + 1, "one shortcut must make one undo step");
      helper.assertTrue(workspace.parts().getFirst().sourceSnapshot().equals(original.getFirst().blocks()), "source snapshot changed");
      helper.assertTrue(workspace.undo(), "shortcut did not support undo");
      helper.assertTrue(workspace.parts().equals(original), "undo did not restore all selected parts");
      for (int turn = 0; turn < 4; turn++) apply(helper, workspace, WorkspaceSymmetry.quarterTurnY());
      for (int index = 0; index < 2; index++) {
         helper.assertTrue(resolved(workspace.parts().get(index)).equals(original.get(index).blocks()), "four turns did not restore the original content");
      }
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         var before = workspace.parts();
         apply(helper, workspace, WorkspaceSymmetry.reflection(axis));
         apply(helper, workspace, WorkspaceSymmetry.reflection(axis));
         for (int index = 0; index < 2; index++) helper.assertTrue(resolved(workspace.parts().get(index)).equals(resolved(before.get(index))), "double flip changed content");
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void clipboardQuarterTurnsDoNotDriftWhenAxisWidthsHaveDifferentParity(GameTestHelper helper) {
      var workspace = new ClientOperationWorkspace();
      var blocks = Map.of(BlockPos.ZERO, new ClientBlockSnapshot(Blocks.STONE.defaultBlockState(), null),
         new BlockPos(1, 0, 0), new ClientBlockSnapshot(Blocks.OAK_STAIRS.defaultBlockState(), null));
      workspace.addParts(List.of(new ClientSelectionPart(0, ClientSelectionPart.Source.CLIPBOARD, null, blocks,
         WorkspaceTransform.IDENTITY.withTranslation(new Vec3(10, 3, -7)), false)));
      var before = resolved(workspace.parts().getFirst());
      for (int turn = 0; turn < 4; turn++) apply(helper, workspace, WorkspaceSymmetry.quarterTurnY());
      helper.assertTrue(resolved(workspace.parts().getFirst()).equals(before), "four clipboard turns moved the group centre");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void mirrorsPreserveStairGeometryWaterAndPistonFacing(GameTestHelper helper) {
      Vec3 center = new Vec3(0.5, 0.5, 0.5);
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         var symmetry = WorkspaceSymmetry.IDENTITY.then(WorkspaceSymmetry.reflection(axis), center);
         for (Direction facing : StairBlock.FACING.getPossibleValues()) {
            for (Half half : Half.values()) {
               for (StairsShape shape : StairsShape.values()) {
                  BlockState source = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing)
                     .setValue(StairBlock.HALF, half).setValue(StairBlock.SHAPE, shape).setValue(StairBlock.WATERLOGGED, true);
                  BlockState mirrored = BlockStateRotation.transform(source, symmetry);
                  VoxelShape expected = transformedShape(source, symmetry);
                  helper.assertTrue(!Shapes.joinIsNotEmpty(expected, mirrored.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), BooleanOp.NOT_SAME),
                     "stair geometry differs after " + axis + " reflection: " + source);
                  helper.assertTrue(mirrored.getValue(StairBlock.WATERLOGGED), "mirror changed water");
                  helper.assertTrue(BlockStateRotation.transform(mirrored, symmetry).equals(source), "double mirror changed stair state");
               }
            }
         }
         for (Direction facing : Direction.values()) {
            BlockState source = Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
            BlockState mirrored = BlockStateRotation.transform(source, symmetry);
            Vec3 expected = symmetry.vector(new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ()));
            helper.assertTrue(mirrored.getValue(BlockStateProperties.FACING) == Direction.fromDelta((int)expected.x, (int)expected.y, (int)expected.z),
               "piston head facing differs after reflection");
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void symmetrySurvivesDraftAndSubmissionCodecs(GameTestHelper helper) throws Exception {
      var workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(part(BlockPos.ZERO), part(new BlockPos(4, 0, 0))));
      apply(helper, workspace, WorkspaceSymmetry.quarterTurnY());
      apply(helper, workspace, WorkspaceSymmetry.reflection(AxisGizmo.Axis.X));
      var registry = helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK);
      var plan = new OperationWorkspacePlan(WorkspaceContentPreparer.confirmedParts(workspace.parts()));
      var decoded = OperationWorkspacePlanCodec.decodeCompressed(OperationWorkspacePlanCodec.encodeCompressed(plan), registry);
      helper.assertTrue(plan.equals(decoded), "submission codec lost symmetry or source data");
      for (int index = 0; index < plan.parts().size(); index++) {
         var submitted = decoded.parts().get(index);
         helper.assertTrue(resolved(workspace.parts().get(index)).equals(WorkspacePreviewComposer.resolveSnapshots(submitted.blocks(), submitted.transform())),
            "submission and preview differ");
      }
      var draft = new ClientOperationDraft(null, workspace.draftState(),
         new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID, List.of(), 0, null, null));
      helper.assertTrue(ClientOperationDraftCodec.decode(ClientOperationDraftCodec.encode(draft), registry).workspace().equals(draft.workspace()),
         "draft codec lost symmetry");
      var legacy = OperationWorkspacePlanCodec.encode(plan);
      legacy.putInt("Version", 1);
      legacy.getList("Parts", net.minecraft.nbt.Tag.TAG_COMPOUND).forEach(tag -> ((net.minecraft.nbt.CompoundTag)tag).remove("Symmetry"));
      helper.assertTrue(OperationWorkspacePlanCodec.decode(legacy, registry).parts().stream()
         .allMatch(part -> part.transform().symmetry().equals(WorkspaceSymmetry.IDENTITY)), "legacy plans must use identity symmetry");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void localRotationAfterShortcutsKeepsCellsAndFacingsTogether(GameTestHelper helper) {
      var blocks = new java.util.LinkedHashMap<BlockPos, ClientBlockSnapshot>();
      for (Direction direction : Direction.values()) {
         blocks.put(BlockPos.ZERO.relative(direction), new ClientBlockSnapshot(Blocks.PISTON_HEAD.defaultBlockState()
            .setValue(BlockStateProperties.FACING, direction), null));
      }
      var volume = OperationSelectionVolume.cuboid(new BlockPos(-1, -1, -1), new BlockPos(1, 1, 1), null, null);
      Vec3 pivot = new Vec3(0.5, 0.5, 0.5);
      for (AxisGizmo.Axis mirror : AxisGizmo.Axis.values()) {
         var symmetry = WorkspaceSymmetry.IDENTITY.then(WorkspaceSymmetry.quarterTurnY(), pivot)
            .then(WorkspaceSymmetry.reflection(mirror), pivot);
         var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, volume, blocks,
            WorkspaceTransform.IDENTITY.withSymmetry(symmetry), false).fixed();
         var before = resolved(part);
         for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
            Vec3 normal = WorkspaceRotation.localAxis(part.transform(), axis);
            var result = SelectionTransformCalculator.calculate(List.of(part), false, AxisGizmo.Operation.ROTATE,
               axis, 1, 256, Math.PI / 2, normal, pivot);
            helper.assertTrue(result.failureKey() == null, "local rotation failed its budget");
            var after = resolved(result.parts().getFirst());
            for (var entry : before.entrySet()) {
               Vec3 point = WorkspaceRotation.rotateAroundAxis(Vec3.atCenterOf(entry.getKey()).subtract(pivot), normal, Math.PI / 2).add(pivot);
               BlockPos target = BlockPos.containing(point);
               Direction facing = entry.getValue().state().getValue(BlockStateProperties.FACING);
               Vec3 rotatedFacing = WorkspaceRotation.rotateAroundAxis(new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ()), normal, Math.PI / 2);
               Direction expected = Direction.fromDelta((int)Math.round(rotatedFacing.x), (int)Math.round(rotatedFacing.y), (int)Math.round(rotatedFacing.z));
               helper.assertTrue(after.containsKey(target), "cell missed the captured local rotation axis");
               helper.assertTrue(after.get(target).state().getValue(BlockStateProperties.FACING) == expected,
                  "piston head did not follow its cell after shortcut and local rotation");
            }
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void rotatedRepeatHandlesMeasureTheLocalWidth(GameTestHelper helper) {
      var blocks = new java.util.LinkedHashMap<BlockPos, ClientBlockSnapshot>();
      for (int x = 0; x < 5; x++) blocks.put(new BlockPos(x, 0, 0), new ClientBlockSnapshot(Blocks.STONE.defaultBlockState(), null));
      var volume = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(4, 0, 0), null, null);
      var symmetry = WorkspaceSymmetry.IDENTITY.then(WorkspaceSymmetry.quarterTurnY(), new Vec3(2.5, 0.5, 0.5));
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, volume, blocks,
         WorkspaceTransform.IDENTITY.withSymmetry(symmetry), false).fixed();
      helper.assertTrue(RepeatStrideSemantics.stride(AxisGizmo.Operation.SCALE, false, part.id(), List.of(part), AxisGizmo.Axis.X) == 5,
         "local X repeat handle used the world X width");
      var result = SelectionTransformCalculator.calculate(List.of(part), false, AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1, 1, 0);
      var after = resolved(result.parts().getFirst());
      helper.assertTrue(after.size() == 10, "repeat copies overlapped because the local stride was wrong");
      var bounds = io.github.fastformer.workspace.selection.OccupiedBlockBounds.from(after.keySet()).orElseThrow();
      helper.assertTrue(bounds.width(AxisGizmo.Axis.Z) == 10 && bounds.width(AxisGizmo.Axis.X) == 1,
         "repeat did not extend along the displayed local handle");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void rotatedScaleKeepsExistingCellsAndExtendsAlongLocalHandles(GameTestHelper helper) {
      var blocks = new java.util.LinkedHashMap<BlockPos, ClientBlockSnapshot>();
      for (int x = 0; x < 5; x++) blocks.put(new BlockPos(x, 0, 0), new ClientBlockSnapshot(Blocks.STONE.defaultBlockState(), null));
      var volume = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(4, 0, 0), null, null);
      Vec3 pivot = new Vec3(2.5, 0.5, 0.5);
      for (Vec3 rotation : List.of(new Vec3(0, Math.PI / 2, 0), new Vec3(0, Math.atan2(3, 4), 0), new Vec3(0.37, 0.63, -0.29))) {
         for (var symmetry : List.of(WorkspaceSymmetry.IDENTITY,
            WorkspaceSymmetry.IDENTITY.then(WorkspaceSymmetry.reflection(AxisGizmo.Axis.Z), pivot))) {
            var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, volume, blocks,
               WorkspaceTransform.IDENTITY.withRotation(rotation).withSymmetry(symmetry), false).fixed();
            var before = resolved(part);
            var beforeBox = io.github.fastformer.workspace.preview.WorkspaceSelectionBounds.resolve(part);
            for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) for (int direction : new int[]{-1, 1}) {
               var result = SelectionTransformCalculator.calculate(List.of(part), false, AxisGizmo.Operation.SCALE, axis, direction, 1, 0);
               helper.assertTrue(result.failureKey() == null, "scale failed");
               var expanded = result.parts().getFirst();
               var after = resolved(expanded);
               helper.assertTrue(after.keySet().containsAll(before.keySet()), "scale moved existing cells after rotation");
               var afterBox = io.github.fastformer.workspace.preview.WorkspaceSelectionBounds.resolve(expanded);
               double stride = RepeatStrideSemantics.stride(AxisGizmo.Operation.SCALE, false, part.id(), List.of(part), axis);
               Vec3 expectedShift = WorkspaceRotation.localAxis(part.transform(), axis).scale(stride * direction * 0.5);
               helper.assertTrue(afterBox.getCenter().subtract(beforeBox.getCenter()).distanceTo(expectedShift) < 1.0E-7,
                  "scale envelope moved away from the displayed local handle");
               var second = SelectionTransformCalculator.calculate(List.of(expanded), false, AxisGizmo.Operation.SCALE, axis, direction, 1, 0).parts().getFirst();
               helper.assertTrue(RepeatStrideSemantics.stride(AxisGizmo.Operation.SCALE, false, second.id(), List.of(second), axis) == stride,
                  "a later scale gesture changed the original repeat unit");
               var oneLess = SelectionTransformCalculator.calculate(List.of(second), false, AxisGizmo.Operation.SCALE, axis, direction, -1, 0).parts().getFirst();
               helper.assertTrue(resolved(oneLess).equals(after), "a later scale gesture removed more than one saved cell");
               var restored = SelectionTransformCalculator.calculate(List.of(expanded), false, AxisGizmo.Operation.SCALE, axis, direction, -1, 0);
               helper.assertTrue(resolved(restored.parts().getFirst()).equals(before), "shrinking the repeat did not restore existing cells");
            }
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void rotationAfterRepeatingKeepsTheWholeSelectionCentre(GameTestHelper helper) {
      var blocks = new java.util.LinkedHashMap<BlockPos, ClientBlockSnapshot>();
      for (int x = 0; x < 5; x++) blocks.put(new BlockPos(x, 0, 0), new ClientBlockSnapshot(Blocks.STONE.defaultBlockState(), null));
      var volume = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(4, 0, 0), null, null);
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, volume, blocks, WorkspaceTransform.IDENTITY, false).fixed();
      part = SelectionTransformCalculator.calculate(List.of(part), false, AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1, 1, 0).parts().getFirst();
      Vec3 center = io.github.fastformer.workspace.preview.WorkspaceSelectionBounds.resolve(part).getCenter();
      var rotated = SelectionTransformCalculator.calculate(List.of(part), false, AxisGizmo.Operation.ROTATE, AxisGizmo.Axis.Y,
         1, 256, Math.PI / 2, WorkspaceRotation.unitAxis(AxisGizmo.Axis.Y), center);
      var after = resolved(rotated.parts().getFirst());
      for (BlockPos original : resolved(part).keySet()) {
         BlockPos expected = BlockPos.containing(WorkspaceRotation.rotateAroundAxis(Vec3.atCenterOf(original).subtract(center),
            WorkspaceRotation.unitAxis(AxisGizmo.Axis.Y), Math.PI / 2).add(center));
         helper.assertTrue(after.containsKey(expected), "rotation after scaling did not use the whole selection centre");
      }
      helper.succeed();
   }

   private static ClientSelectionPart part(BlockPos point) {
      return new ClientSelectionPart(0, ClientSelectionPart.Source.WORLD,
         OperationSelectionVolume.cuboid(point, point, point, point),
         Map.of(point, new ClientBlockSnapshot(Blocks.OAK_STAIRS.defaultBlockState(), null)), WorkspaceTransform.IDENTITY, false);
   }

   private static Map<BlockPos, ClientBlockSnapshot> resolved(ClientSelectionPart part) {
      return WorkspacePreviewComposer.resolveSnapshots(part.blocks(), part.transform());
   }

   private static void apply(GameTestHelper helper, ClientOperationWorkspace workspace, WorkspaceSymmetry operation) {
      var result = SelectionShortcutCalculator.calculate(workspace.selectedParts(), operation);
      helper.assertTrue(result.failureKey() == null, "shortcut exceeded its budget");
      helper.assertTrue(workspace.beginEdit(), "shortcut could not open an edit");
      result.parts().forEach(workspace::updatePart);
      workspace.finishEdit();
   }

   private static VoxelShape transformedShape(BlockState state, WorkspaceSymmetry symmetry) {
      VoxelShape expected = Shapes.empty();
      for (var box : state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
         Vec3 first = symmetry.point(new Vec3(box.minX, box.minY, box.minZ));
         Vec3 second = symmetry.point(new Vec3(box.maxX, box.maxY, box.maxZ));
         expected = Shapes.or(expected, Shapes.box(Math.min(first.x, second.x), Math.min(first.y, second.y), Math.min(first.z, second.z),
            Math.max(first.x, second.x), Math.max(first.y, second.y), Math.max(first.z, second.z)));
      }
      return expected;
   }
}
