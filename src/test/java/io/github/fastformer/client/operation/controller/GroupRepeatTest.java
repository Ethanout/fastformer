package io.github.fastformer.client.operation.controller;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.core.math.Axis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;
import io.github.fastformer.common.workspace.composition.WorkspaceComposer;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.common.selection.OperationStackRegion;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
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
         ClientSessionSystem.instance().operations().onDisconnected();
         var source = cuboidPart(1, BlockPos.ZERO, 5, 0, 0);
         assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(source)));
         var original = ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow();
         int history = ClientSessionSystem.instance().operations().workspace().undoSize();
         for (int steps : new int[]{3, -1}) {
            assertTrue(ClientSessionSystem.instance().operations().workspace().beginEdit());
            var edit = ClientSessionSystem.instance().operations().workspace().activeEditToken();
            var baseline = ClientSessionSystem.instance().operations().workspace().selectedParts();
            assertNull(ClientSessionSystem.instance().operations().transforms().updateTransformGesture(edit, baseline, false,
               AxisGizmo.Operation.SCALE, Axis.X, 1, steps, Double.NaN));
            assertTrue(ClientSessionSystem.instance().operations().transforms().finishTransformGesture(edit));
         }
         var adjusted = ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow();
         assertEquals(2, adjusted.transform().repeats().max().getX());
         assertEquals(5, adjusted.transform().repeatStride().getX());
         assertEquals(original.selection(), adjusted.selection());
         assertEquals(original.blocks(), adjusted.blocks());
         assertEquals(original.sourceSnapshot(), adjusted.sourceSnapshot());
         assertEquals(15, resolved(adjusted).size());
         assertEquals(history + 2, ClientSessionSystem.instance().operations().workspace().undoSize());
         assertTrue(ClientSessionSystem.instance().operations().workspace().undo());
         assertEquals(3, ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow().transform().repeats().max().getX());
         assertTrue(ClientSessionSystem.instance().operations().workspace().undo());
         assertEquals(original, ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow());
      } finally {
         ClientSessionSystem.instance().operations().onDisconnected();
      }
   }

   @Test
   void rejectedCommonRotationReturnsOneMessagePerEditAndKeepsTheWorkspace() {
      try {
         for (boolean workLimit : List.of(false, true)) {
            ClientSessionSystem.instance().operations().onDisconnected();
            ClientSelectionPart first = cuboidPart(1, BlockPos.ZERO, 1, 0, 0);
            WorkspaceTransform oversized = workLimit
               ? first.transform().withScale(Axis.X, 9_000_000)
               : first.transform().withRepeats(
                  new OperationStackRegion(BlockPos.ZERO, new BlockPos(100, 100, 100)),
                  new BlockPos(1, 1, 1)
               );
            first = first.withTransform(oversized);
            assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(
               first, cuboidPart(2, new BlockPos(2, 0, 0), 1, 0, 0)
            )));
            String expected = workLimit ? "fastformer.message.workspace_work_too_large"
               : "fastformer.message.workspace_result_too_large";
            for (int attempt = 0; attempt < 2; attempt++) {
               assertTrue(ClientSessionSystem.instance().operations().workspace().beginEdit());
               var edit = ClientSessionSystem.instance().operations().workspace().activeEditToken();
               var baseline = ClientSessionSystem.instance().operations().workspace().selectedParts();
               long revision = ClientSessionSystem.instance().operations().workspace().revision();

               assertEquals(expected, ClientSessionSystem.instance().operations().transforms().updateTransformGesture(
                  edit, baseline, true, AxisGizmo.Operation.ROTATE, Axis.Y, 1, 1, 0.1
               ));
               assertNull(ClientSessionSystem.instance().operations().transforms().updateTransformGesture(
                  edit, baseline, true, AxisGizmo.Operation.ROTATE, Axis.Y, 1, 2, 0.2
               ));
               assertEquals(baseline, ClientSessionSystem.instance().operations().workspace().selectedParts());
               assertEquals(revision, ClientSessionSystem.instance().operations().workspace().revision());
               ClientSessionSystem.instance().operations().transforms().finishTransformGesture(edit);
            }
         }
      } finally {
         ClientSessionSystem.instance().operations().onDisconnected();
      }
   }

   @Test
   void largeCommonSelectionCanMoveAndReduceItsRepeatCountWithoutVoxelExpansion() {
      try {
         for (AxisGizmo.Operation operation : List.of(AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE)) {
            ClientSessionSystem.instance().operations().onDisconnected();
            ClientSelectionPart first = cuboidPart(1, BlockPos.ZERO, 1, 0, 0);
            first = first.withTransform(first.transform().withRepeats(
               new OperationStackRegion(BlockPos.ZERO, new BlockPos(100, 100, 100)),
               new BlockPos(1, 1, 1)
            ));
            ClientSelectionPart second = cuboidPart(2, new BlockPos(2, 0, 0), 1, 0, 0);
            assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(first, second)));
            assertTrue(ClientSessionSystem.instance().operations().workspace().beginEdit());
            var edit = ClientSessionSystem.instance().operations().workspace().activeEditToken();
            var baseline = ClientSessionSystem.instance().operations().workspace().selectedParts();

            ClientSessionSystem.instance().operations().transforms().updateTransformGesture(
               edit, baseline, true, operation, Axis.X, 1, -1, Double.NaN
            );

            var updated = ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow();
            if (operation == AxisGizmo.Operation.MOVE) {
               assertEquals(-1.0, updated.transform().translation().x);
               assertEquals(first.transform().repeats(), updated.transform().repeats());
            } else {
               assertTrue(updated.transform().repeats().max().getX() < 100);
            }
         }
      } finally {
         ClientSessionSystem.instance().operations().onDisconnected();
      }
   }

   /** Two parts, five and two wide, with six blocks of air between them. */
   @Test
   void firstCommonStackUsesTheWholeGroupBoxIncludingTheAirBetweenParts() {
      ClientSessionSystem.instance().operations().onDisconnected();
      ClientSelectionPart first = cuboidPart(1, BlockPos.ZERO, 5, 0, 0);
      ClientSelectionPart second = cuboidPart(2, new BlockPos(10, 0, 0), 2, 0, 0);
      assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(first, second)));
      assertTrue(ClientSessionSystem.instance().operations().workspace().beginEdit());
      var edit = ClientSessionSystem.instance().operations().workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientSessionSystem.instance().operations().workspace().selectedParts();

      ClientSessionSystem.instance().operations().transforms().updateTransformGesture(
         edit, baseline, true, AxisGizmo.Operation.SCALE, Axis.X, 1, 1, Double.NaN
      );

      ClientSelectionPart updatedFirst = ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow();
      ClientSelectionPart updatedSecond = ClientSessionSystem.instance().operations().workspace().part(2).orElseThrow();
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
      ClientSessionSystem.instance().operations().onDisconnected();
   }

   /** A later gesture adds one base group, keeping the copies already placed. */
   @Test
   void commonSecondStackRepeatsTheWholeGroupAndKeepsBothParts() {
      ClientSessionSystem.instance().operations().onDisconnected();
      ClientSelectionPart first = cuboidPart(1, BlockPos.ZERO, 5, 1, 12);
      ClientSelectionPart second = cuboidPart(2, new BlockPos(10, 0, 0), 2, 1, 12);
      assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(first, second)));
      assertTrue(ClientSessionSystem.instance().operations().workspace().beginEdit());
      var edit = ClientSessionSystem.instance().operations().workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientSessionSystem.instance().operations().workspace().selectedParts();

      ClientSessionSystem.instance().operations().transforms().updateTransformGesture(
         edit, baseline, true, AxisGizmo.Operation.SCALE, Axis.X, 1, 1, Double.NaN
      );

      ClientSelectionPart updatedFirst = ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow();
      ClientSelectionPart updatedSecond = ClientSessionSystem.instance().operations().workspace().part(2).orElseThrow();
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
      ClientSessionSystem.instance().operations().onDisconnected();
   }

   /**
    * A prism part changes its own size, so the input keeps a block-valued step for the whole
    * gesture. The cuboid part must not multiply that block-valued step by the group cells.
    */
   @Test
   void mixedPrismGroupKeepsTheBlockValuedStepUnamplified() {
      ClientSessionSystem.instance().operations().onDisconnected();
      ClientSelectionPart cuboid = cuboidPart(1, BlockPos.ZERO, 5, 1, 5);
      ClientSelectionPart prism = prismPart(2);
      assertTrue(prism.selection() != null && prism.selection().prism() != null);
      assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(cuboid, prism)));
      assertTrue(ClientSessionSystem.instance().operations().workspace().beginEdit());
      var edit = ClientSessionSystem.instance().operations().workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientSessionSystem.instance().operations().workspace().selectedParts();

      // Three blocks of travel, as FastPlaceClientInput sends for a gesture without
      // whole-group repeats.
      ClientSessionSystem.instance().operations().transforms().updateTransformGesture(
         edit, baseline, true, AxisGizmo.Operation.SCALE, Axis.X, 1, 3, Double.NaN
      );

      ClientSelectionPart updatedCuboid = ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow();
      ClientSelectionPart updatedPrism = ClientSessionSystem.instance().operations().workspace().part(2).orElseThrow();
      // Three cells, not three groups of two cells.
      assertEquals(4, updatedCuboid.transform().repeats().max().getX());
      assertEquals(5, updatedCuboid.transform().repeatStride().getX());
      // The prism still changes its own size by the same three blocks.
      assertEquals(4.0, updatedPrism.transform().scale().x);
      ClientSessionSystem.instance().operations().onDisconnected();
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
      ClientSessionSystem.instance().operations().onDisconnected();
      // A was stacked alone, so its copies sit five blocks apart. B was stacked alone with a
      // two block stride. The whole therefore spans x=0..13.
      ClientSelectionPart first = cuboidPart(1, BlockPos.ZERO, 5, 1, 5);
      ClientSelectionPart second = cuboidPart(2, new BlockPos(10, 0, 0), 2, 1, 2);
      assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(first, second)));
      assertTrue(ClientSessionSystem.instance().operations().workspace().beginEdit());
      var edit = ClientSessionSystem.instance().operations().workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientSessionSystem.instance().operations().workspace().selectedParts();

      ClientSessionSystem.instance().operations().transforms().updateTransformGesture(
         edit, baseline, true, AxisGizmo.Operation.SCALE, Axis.X, 1, 1, Double.NaN
      );

      Set<BlockPos> firstBlocks = resolved(ClientSessionSystem.instance().operations().workspace().part(1).orElseThrow());
      Set<BlockPos> secondBlocks = resolved(ClientSessionSystem.instance().operations().workspace().part(2).orElseThrow());
      assertTrue(firstBlocks.containsAll(xRange(0, 9)));
      assertTrue(secondBlocks.containsAll(xRange(10, 13)));
      assertTrue(firstBlocks.containsAll(xRange(14, 23)));
      assertTrue(secondBlocks.containsAll(xRange(24, 27)));
      ClientSessionSystem.instance().operations().onDisconnected();
   }

   private static ClientSelectionPart cuboidPart(int id, BlockPos origin, int width, int lastRepeat, int stride) {
      OperationSelectionVolume selection = OperationSelectionVolume.cuboid(
         origin, origin.offset(width - 1, 0, 0), origin, origin
      );
      LinkedHashMap<BlockPos, BlockSnapshot> blocks = new LinkedHashMap<>();
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
      return WorkspaceComposer.resolve(part).keySet();
   }

   private static Set<BlockPos> xRange(int from, int to) {
      Set<BlockPos> positions = new LinkedHashSet<>();
      for (int x = from; x <= to; x++) {
         positions.add(new BlockPos(x, 0, 0));
      }
      return positions;
   }

   private static BlockSnapshot snapshot() {
      try {
         Field field = Unsafe.class.getDeclaredField("theUnsafe");
         field.setAccessible(true);
         return (BlockSnapshot)((Unsafe)field.get(null)).allocateInstance(BlockSnapshot.class);
      } catch (ReflectiveOperationException exception) {
         throw new AssertionError(exception);
      }
   }
}
