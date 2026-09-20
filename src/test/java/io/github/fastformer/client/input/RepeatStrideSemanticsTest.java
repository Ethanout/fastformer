package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.preview.WorkspaceSelectionBounds;
import io.github.fastformer.client.operation.transform.RepeatDragQuantizer;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Input quantization for stack (SCALE) drags. The writer repeats one whole
 * group per step, so the travel that adds a step is the whole selection box.
 */
class RepeatStrideSemanticsTest {
   @Test
   void secondStackTravelsOneWholeGroupPerStep() {
      // A five wide selection that already holds the copy of one earlier stack.
      ClientSelectionPart stacked = stackedCuboid(1, 4, 1, 5);

      // The cell box is one copy. The old input source returned this value, so
      // the same travel added two groups after a stack.
      assertEquals(5, WorkspaceSelectionBounds.extent(
         WorkspaceSelectionBounds.resolveBase(stacked), AxisGizmo.Axis.X
      ));
      assertEquals(10, RepeatStrideSemantics.stride(
         AxisGizmo.Operation.SCALE, false, 1, List.of(stacked), AxisGizmo.Axis.X
      ));

      // One step per whole group: the drag must travel both copies.
      assertEquals(0, RepeatDragQuantizer.copiesForOffset(9.99, 10));
      assertEquals(1, RepeatDragQuantizer.copiesForOffset(10.0, 10));
      assertEquals(2, RepeatDragQuantizer.copiesForOffset(20.0, 10));
   }

   @Test
   void selectionEnvelopeAirCountsInTheStride() {
      // The selection box is five wide and holds no block at all.
      ClientSelectionPart empty = cuboid(2, 0, 4, WorkspaceTransform.IDENTITY);

      assertEquals(5, RepeatStrideSemantics.stride(
         AxisGizmo.Operation.SCALE, false, 2, List.of(empty), AxisGizmo.Axis.X
      ));
      assertEquals(1, RepeatStrideSemantics.stride(
         AxisGizmo.Operation.SCALE, false, 2, List.of(empty), AxisGizmo.Axis.Y
      ));
   }

   @Test
   void commonGroupStrideUsesTheSelectionBoxUnion() {
      ClientSelectionPart first = cuboid(1, 0, 4, WorkspaceTransform.IDENTITY);
      ClientSelectionPart second = cuboid(2, 10, 12, WorkspaceTransform.IDENTITY);
      List<ClientSelectionPart> baseline = List.of(first, second);

      // The common handle repeats the whole group, so the union is the unit.
      assertEquals(13, RepeatStrideSemantics.stride(
         AxisGizmo.Operation.SCALE, true, 0, baseline, AxisGizmo.Axis.X
      ));
      // A single part handle keeps its own box, like the writer does.
      assertEquals(5, RepeatStrideSemantics.stride(
         AxisGizmo.Operation.SCALE, false, 1, baseline, AxisGizmo.Axis.X
      ));
      assertEquals(3, RepeatStrideSemantics.stride(
         AxisGizmo.Operation.SCALE, false, 2, baseline, AxisGizmo.Axis.X
      ));
   }

   @Test
   void otherGesturesAndPrismsKeepTheCellStride() {
      ClientSelectionPart part = cuboid(1, 0, 4, WorkspaceTransform.IDENTITY);
      List<ClientSelectionPart> baseline = List.of(part);

      assertEquals(1, RepeatStrideSemantics.stride(
         AxisGizmo.Operation.MOVE, false, 1, baseline, AxisGizmo.Axis.X
      ));
      assertEquals(1, RepeatStrideSemantics.stride(
         AxisGizmo.Operation.ROTATE, true, 1, baseline, AxisGizmo.Axis.X
      ));
      assertEquals(1, RepeatStrideSemantics.stride(
         AxisGizmo.Operation.SCALE, false, 99, baseline, AxisGizmo.Axis.X
      ));
      assertEquals(1, RepeatStrideSemantics.stride(null, AxisGizmo.Axis.X));
      assertEquals(true, RepeatStrideSemantics.repeatsWholeGroup(AxisGizmo.Operation.SCALE, baseline));
      assertEquals(false, RepeatStrideSemantics.repeatsWholeGroup(AxisGizmo.Operation.MOVE, baseline));
      assertEquals(false, RepeatStrideSemantics.repeatsWholeGroup(AxisGizmo.Operation.SCALE, null));
   }

   @Test
   void aPrismPartNeverRepeatsWholeGroups() {
      ClientSelectionPart prism = prismPart(3);
      OperationSelectionVolume selection = prism.selection();

      assertEquals(true, selection != null && selection.prism() != null);
      assertEquals(false, RepeatStrideSemantics.repeatsWholeGroup(AxisGizmo.Operation.SCALE, List.of(prism)));
      assertEquals(1, RepeatStrideSemantics.stride(
         AxisGizmo.Operation.SCALE, false, 3, List.of(prism), AxisGizmo.Axis.X
      ));
   }

   /** A cuboid part of the given width with no repeats. */
   private static ClientSelectionPart cuboid(int id, int minX, int maxX, WorkspaceTransform transform) {
      return new ClientSelectionPart(
         id, ClientSelectionPart.Source.WORLD, cuboidVolume(minX, maxX), Map.of(), transform, false
      );
   }

   /** A cuboid part that already holds one copy of an earlier stack. */
   private static ClientSelectionPart stackedCuboid(int id, int maxX, int copies, int stride) {
      return cuboid(id, 0, maxX, new WorkspaceTransform(
         Vec3.ZERO,
         Vec3.ZERO,
         new OperationStackRegion(BlockPos.ZERO, new BlockPos(copies, 0, 0)),
         new BlockPos(stride, 0, 0)
      ));
   }

   /** A prism part with a square base and one height point. */
   private static ClientSelectionPart prismPart(int id) {
      OperationSelectionVolume selection = OperationSelectionVolume.create(
         OperationSelectionMode.PRISM,
         List.of(
            new BlockPos(0, 0, 0),
            new BlockPos(2, 0, 0),
            new BlockPos(2, 0, 2),
            new BlockPos(0, 0, 2),
            new BlockPos(0, 2, 0)
         ),
         BlockPos.ZERO,
         BlockPos.ZERO,
         0
      );
      return new ClientSelectionPart(
         id, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );
   }

   private static OperationSelectionVolume cuboidVolume(int minX, int maxX) {
      BlockPos low = new BlockPos(minX, 0, 0);
      BlockPos high = new BlockPos(maxX, 0, 0);
      return OperationSelectionVolume.cuboid(low, high, low, high);
   }
}
