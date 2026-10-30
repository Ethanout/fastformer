package io.github.fastformer.client.operation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.preview.WorkspacePreviewComposer;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * Whole-group repeat model for a common (multi-part) stack gesture.
 *
 * <p>The unit of one repeat step is the base group envelope, including selected air.
 * The record {@code repeatStride} keeps this extent between gestures.
 *
 * <p>See docs/reviews/2026-10-25-project-audit.md for the remaining gaps.
 */
class GroupRepeatTest {
   @Test
   void releasedScaleKeepsItsSourceAndShrinksOneSavedCellAtATime() {
      try {
         ClientOperationController.onDisconnected();
         var source = cuboidPart(1, BlockPos.ZERO, 5, 0, 0);
         assertTrue(ClientOperationController.workspace().addParts(List.of(source)));
         var original = ClientOperationController.workspace().part(1).orElseThrow();
         int history = ClientOperationController.workspace().undoSize();
         for (int steps : new int[]{3, -1}) {
            assertTrue(ClientOperationController.workspace().beginEdit());
            var edit = ClientOperationController.workspace().activeEditToken();
            var baseline = ClientOperationController.workspace().selectedParts();
            assertNull(ClientOperationController.updateTransformGesture(edit, baseline, false,
               AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1, steps, Double.NaN));
            assertTrue(ClientOperationController.finishTransformGesture(edit));
         }
         var adjusted = ClientOperationController.workspace().part(1).orElseThrow();
         assertEquals(2, adjusted.transform().repeats().max().getX());
         assertEquals(5, adjusted.transform().repeatStride().getX());
         assertEquals(original.selection(), adjusted.selection());
         assertEquals(original.blocks(), adjusted.blocks());
         assertEquals(original.sourceSnapshot(), adjusted.sourceSnapshot());
         assertEquals(15, resolved(adjusted).size());
         assertEquals(history + 2, ClientOperationController.workspace().undoSize());
         assertTrue(ClientOperationController.workspace().undo());
         assertEquals(3, ClientOperationController.workspace().part(1).orElseThrow().transform().repeats().max().getX());
         assertTrue(ClientOperationController.workspace().undo());
         assertEquals(original, ClientOperationController.workspace().part(1).orElseThrow());
      } finally {
         ClientOperationController.onDisconnected();
      }
   }

   @Test
   void rejectedCommonRotationReturnsOneMessagePerEditAndKeepsTheWorkspace() {
      try {
         for (boolean workLimit : List.of(false, true)) {
            ClientOperationController.onDisconnected();
            ClientSelectionPart first = cuboidPart(1, BlockPos.ZERO, 1, 0, 0);
            WorkspaceTransform oversized = workLimit
               ? first.transform().withScale(AxisGizmo.Axis.X, 9_000_000)
               : first.transform().withRepeats(
                  new OperationStackRegion(BlockPos.ZERO, new BlockPos(100, 100, 100)),
                  new BlockPos(1, 1, 1)
               );
            first = first.withTransform(oversized);
            assertTrue(ClientOperationController.workspace().addParts(List.of(
               first, cuboidPart(2, new BlockPos(2, 0, 0), 1, 0, 0)
            )));
            String expected = workLimit ? "fastformer.message.workspace_work_too_large"
               : "fastformer.message.workspace_result_too_large";
            for (int attempt = 0; attempt < 2; attempt++) {
               assertTrue(ClientOperationController.workspace().beginEdit());
               var edit = ClientOperationController.workspace().activeEditToken();
               var baseline = ClientOperationController.workspace().selectedParts();
               long revision = ClientOperationController.workspace().revision();

               assertEquals(expected, ClientOperationController.updateTransformGesture(
                  edit, baseline, true, AxisGizmo.Operation.ROTATE, AxisGizmo.Axis.Y, 1, 1, 0.1
               ));
               assertNull(ClientOperationController.updateTransformGesture(
                  edit, baseline, true, AxisGizmo.Operation.ROTATE, AxisGizmo.Axis.Y, 1, 2, 0.2
               ));
               assertEquals(baseline, ClientOperationController.workspace().selectedParts());
               assertEquals(revision, ClientOperationController.workspace().revision());
               ClientOperationController.finishTransformGesture(edit);
            }
         }
      } finally {
         ClientOperationController.onDisconnected();
      }
   }

   @Test
   void largeCommonSelectionCanMoveAndReduceItsRepeatCountWithoutVoxelExpansion() {
      try {
         for (AxisGizmo.Operation operation : List.of(AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE)) {
            ClientOperationController.onDisconnected();
            ClientSelectionPart first = cuboidPart(1, BlockPos.ZERO, 1, 0, 0);
            first = first.withTransform(first.transform().withRepeats(
               new OperationStackRegion(BlockPos.ZERO, new BlockPos(100, 100, 100)),
               new BlockPos(1, 1, 1)
            ));
            ClientSelectionPart second = cuboidPart(2, new BlockPos(2, 0, 0), 1, 0, 0);
            assertTrue(ClientOperationController.workspace().addParts(List.of(first, second)));
            assertTrue(ClientOperationController.workspace().beginEdit());
            var edit = ClientOperationController.workspace().activeEditToken();
            var baseline = ClientOperationController.workspace().selectedParts();

            ClientOperationController.updateTransformGesture(
               edit, baseline, true, operation, AxisGizmo.Axis.X, 1, -1, Double.NaN
            );

            var updated = ClientOperationController.workspace().part(1).orElseThrow();
            if (operation == AxisGizmo.Operation.MOVE) {
               assertEquals(-1.0, updated.transform().translation().x);
               assertEquals(first.transform().repeats(), updated.transform().repeats());
            } else {
               assertTrue(updated.transform().repeats().max().getX() < 100);
            }
         }
      } finally {
         ClientOperationController.onDisconnected();
      }
   }

   /** Two parts, five and two wide, with six blocks of air between them. */
   @Test
   void firstCommonStackUsesTheWholeGroupBoxIncludingTheAirBetweenParts() {
      ClientOperationController.onDisconnected();
      ClientSelectionPart first = cuboidPart(1, BlockPos.ZERO, 5, 0, 0);
      ClientSelectionPart second = cuboidPart(2, new BlockPos(10, 0, 0), 2, 0, 0);
      assertTrue(ClientOperationController.workspace().addParts(List.of(first, second)));
      assertTrue(ClientOperationController.workspace().beginEdit());
      var edit = ClientOperationController.workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientOperationController.workspace().selectedParts();

      ClientOperationController.updateTransformGesture(
         edit, baseline, true, AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1, 1, Double.NaN
      );

      ClientSelectionPart updatedFirst = ClientOperationController.workspace().part(1).orElseThrow();
      ClientSelectionPart updatedSecond = ClientOperationController.workspace().part(2).orElseThrow();
      // The group box spans x=0..11, so one step is twelve blocks wide, not the occupied runs.
      assertEquals(12, updatedFirst.transform().repeatStride().getX());
      assertEquals(12, updatedSecond.transform().repeatStride().getX());
      assertEquals(1, updatedFirst.transform().repeats().max().getX());
      assertEquals(1, updatedSecond.transform().repeats().max().getX());
      // The second copy of the whole keeps the air between the parts.
      assertTrue(resolved(updatedFirst).containsAll(xRange(0, 4)));
      assertTrue(resolved(updatedFirst).containsAll(xRange(12, 16)));
      assertTrue(resolved(updatedSecond).containsAll(xRange(10, 11)));
      assertTrue(resolved(updatedSecond).containsAll(xRange(22, 23)));
      ClientOperationController.onDisconnected();
   }

   /** A later gesture adds one base group, keeping the copies already placed. */
   @Test
   void commonSecondStackRepeatsTheWholeGroupAndKeepsBothParts() {
      ClientOperationController.onDisconnected();
      ClientSelectionPart first = cuboidPart(1, BlockPos.ZERO, 5, 1, 12);
      ClientSelectionPart second = cuboidPart(2, new BlockPos(10, 0, 0), 2, 1, 12);
      assertTrue(ClientOperationController.workspace().addParts(List.of(first, second)));
      assertTrue(ClientOperationController.workspace().beginEdit());
      var edit = ClientOperationController.workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientOperationController.workspace().selectedParts();

      ClientOperationController.updateTransformGesture(
         edit, baseline, true, AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1, 1, Double.NaN
      );

      ClientSelectionPart updatedFirst = ClientOperationController.workspace().part(1).orElseThrow();
      ClientSelectionPart updatedSecond = ClientOperationController.workspace().part(2).orElseThrow();
      // Releasing the earlier gesture does not change its twelve-block repeat unit.
      assertEquals(12, updatedFirst.transform().repeatStride().getX());
      assertEquals(12, updatedSecond.transform().repeatStride().getX());
      assertEquals(2, updatedFirst.transform().repeats().max().getX());
      assertEquals(2, updatedSecond.transform().repeats().max().getX());
      Set<BlockPos> firstBlocks = resolved(updatedFirst);
      Set<BlockPos> secondBlocks = resolved(updatedSecond);
      // Copies placed by the first stack stay where they are.
      assertTrue(firstBlocks.containsAll(xRange(0, 4)));
      assertTrue(firstBlocks.containsAll(xRange(12, 16)));
      assertTrue(secondBlocks.containsAll(xRange(10, 11)));
      assertTrue(secondBlocks.containsAll(xRange(22, 23)));
      // Exactly one base group is added.
      assertTrue(firstBlocks.containsAll(xRange(24, 28)));
      assertTrue(secondBlocks.containsAll(xRange(34, 35)));
      assertEquals(15, firstBlocks.size());
      assertEquals(6, secondBlocks.size());
      ClientOperationController.onDisconnected();
   }

   /**
    * A prism part changes its own size, so the input keeps a block-valued step for the whole
    * gesture. The cuboid part must not multiply that block-valued step by the group cells.
    */
   @Test
   void mixedPrismGroupKeepsTheBlockValuedStepUnamplified() {
      ClientOperationController.onDisconnected();
      ClientSelectionPart cuboid = cuboidPart(1, BlockPos.ZERO, 5, 1, 5);
      ClientSelectionPart prism = prismPart(2);
      assertTrue(prism.selection() != null && prism.selection().prism() != null);
      assertTrue(ClientOperationController.workspace().addParts(List.of(cuboid, prism)));
      assertTrue(ClientOperationController.workspace().beginEdit());
      var edit = ClientOperationController.workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientOperationController.workspace().selectedParts();

      // Three blocks of travel, as FastPlaceClientInput sends for a gesture without
      // whole-group repeats.
      ClientOperationController.updateTransformGesture(
         edit, baseline, true, AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1, 3, Double.NaN
      );

      ClientSelectionPart updatedCuboid = ClientOperationController.workspace().part(1).orElseThrow();
      ClientSelectionPart updatedPrism = ClientOperationController.workspace().part(2).orElseThrow();
      // Three cells, not three groups of two cells.
      assertEquals(4, updatedCuboid.transform().repeats().max().getX());
      assertEquals(5, updatedCuboid.transform().repeatStride().getX());
      // The prism still changes its own size by the same three blocks.
      assertEquals(4.0, updatedPrism.transform().scale().x);
      ClientOperationController.onDisconnected();
   }

   /**
    * Target behaviour for parts whose placed copies do not share one lattice.
    *
    * <p>Disabled: the current {@link WorkspaceTransform} holds one repeat interval and one
    * stride, so it cannot keep both parts' copies and repeat the whole as a rigid unit. Such
    * a state currently re-spaces both parts onto the base cell lattice. A group repeat
    * layer needs transform and wire-format changes. The deferred scope is recorded in
    * docs/reviews/2026-10-25-project-audit.md.
    */
   @Test
   @Disabled("needs a group repeat layer in WorkspaceTransform; see 2026-10-25-project-audit.md")
   void placedCopiesSurviveACommonSecondStackAfterPerPartFirstStacks() {
      ClientOperationController.onDisconnected();
      // A was stacked alone, so its copies sit five blocks apart. B was stacked alone with a
      // two block stride. The whole therefore spans x=0..13.
      ClientSelectionPart first = cuboidPart(1, BlockPos.ZERO, 5, 1, 5);
      ClientSelectionPart second = cuboidPart(2, new BlockPos(10, 0, 0), 2, 1, 2);
      assertTrue(ClientOperationController.workspace().addParts(List.of(first, second)));
      assertTrue(ClientOperationController.workspace().beginEdit());
      var edit = ClientOperationController.workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientOperationController.workspace().selectedParts();

      ClientOperationController.updateTransformGesture(
         edit, baseline, true, AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1, 1, Double.NaN
      );

      Set<BlockPos> firstBlocks = resolved(ClientOperationController.workspace().part(1).orElseThrow());
      Set<BlockPos> secondBlocks = resolved(ClientOperationController.workspace().part(2).orElseThrow());
      assertTrue(firstBlocks.containsAll(xRange(0, 9)));
      assertTrue(secondBlocks.containsAll(xRange(10, 13)));
      assertTrue(firstBlocks.containsAll(xRange(14, 23)));
      assertTrue(secondBlocks.containsAll(xRange(24, 27)));
      ClientOperationController.onDisconnected();
   }

   private static ClientSelectionPart cuboidPart(int id, BlockPos origin, int width, int lastRepeat, int stride) {
      OperationSelectionVolume selection = OperationSelectionVolume.cuboid(
         origin, origin.offset(width - 1, 0, 0), origin, origin
      );
      LinkedHashMap<BlockPos, ClientBlockSnapshot> blocks = new LinkedHashMap<>();
      for (int offset = 0; offset < width; offset++) {
         blocks.put(origin.offset(offset, 0, 0), snapshot());
      }
      OperationStackRegion repeats = lastRepeat == 0
         ? OperationStackRegion.origin()
         : new OperationStackRegion(BlockPos.ZERO, new BlockPos(lastRepeat, 0, 0));
      return new ClientSelectionPart(
         id,
         ClientSelectionPart.Source.WORLD,
         selection,
         blocks,
         new WorkspaceTransform(Vec3.ZERO, Vec3.ZERO, repeats, new BlockPos(stride, 0, 0)),
         false
      );
   }

   private static ClientSelectionPart prismPart(int id) {
      OperationSelectionVolume selection = OperationSelectionVolume.create(
         OperationSelectionMode.PRISM,
         List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, 0, 2)),
         BlockPos.ZERO,
         BlockPos.ZERO,
         0
      );
      return new ClientSelectionPart(
         id,
         ClientSelectionPart.Source.WORLD,
         selection,
         Map.of(BlockPos.ZERO, snapshot()),
         WorkspaceTransform.IDENTITY,
         false
      );
   }

   private static Set<BlockPos> resolved(ClientSelectionPart part) {
      return WorkspacePreviewComposer.resolve(part).keySet();
   }

   private static Set<BlockPos> xRange(int from, int to) {
      Set<BlockPos> positions = new LinkedHashSet<>();
      for (int x = from; x <= to; x++) {
         positions.add(new BlockPos(x, 0, 0));
      }
      return positions;
   }

   private static ClientBlockSnapshot snapshot() {
      try {
         Field field = Unsafe.class.getDeclaredField("theUnsafe");
         field.setAccessible(true);
         return (ClientBlockSnapshot)((Unsafe)field.get(null)).allocateInstance(ClientBlockSnapshot.class);
      } catch (ReflectiveOperationException exception) {
         throw new AssertionError(exception);
      }
   }
}
