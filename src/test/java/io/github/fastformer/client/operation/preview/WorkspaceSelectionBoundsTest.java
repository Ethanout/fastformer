package io.github.fastformer.client.operation.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorkspaceSelectionBoundsTest {
   @Test
   void halfCellScaleMatchesTheComposedVoxelExtent() {
      var blocks = new java.util.LinkedHashMap<BlockPos, String>();
      for (int x = 0; x < 5; x++) {
         blocks.put(new BlockPos(x, 0, 0), "block");
      }
      for (double scale : new double[] {0.5, 0.9, 1.3}) {
         WorkspaceTransform transform = WorkspaceTransform.IDENTITY.withScale(AxisGizmo.Axis.X, scale);
         var frame = WorkspacePreviewComposer.geometryFrame(blocks, transform);
         AABB envelope = WorkspaceSelectionBounds.transformedBox(new AABB(0, 0, 0, 5, 1, 1), transform, frame);
         AABB occupied = io.github.fastformer.client.operation.selection.OccupiedBlockBounds
            .from(WorkspacePreviewComposer.resolveValues(blocks, transform).keySet()).orElseThrow().aabb();

         assertEquals(occupied.getXsize(), envelope.getXsize(), 1.0E-7, "scale=" + scale);
         assertEquals(occupied.minX, envelope.minX, 1.0E-7, "scale=" + scale);
         assertEquals(occupied.maxX, envelope.maxX, 1.0E-7, "scale=" + scale);
      }
   }

   @Test
   void stackExtentUsesSelectionRangeInsteadOfOccupiedBlocks() {
      OperationSelectionVolume selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(4, 0, 0), BlockPos.ZERO, new BlockPos(4, 0, 0)
      );
      ClientSelectionPart part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );

      AABB bounds = WorkspaceSelectionBounds.resolve(part);

      assertEquals(5, WorkspaceSelectionBounds.extent(bounds, AxisGizmo.Axis.X));
      assertEquals(1, WorkspaceSelectionBounds.extent(bounds, AxisGizmo.Axis.Y));
   }

   @Test
   void wholeGroupBoxIncludesPlacedCopiesWhileTheCellBoxDoesNot() {
      OperationSelectionVolume selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(4, 0, 0), BlockPos.ZERO, new BlockPos(4, 0, 0)
      );
      ClientSelectionPart stacked = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(),
         new WorkspaceTransform(
            Vec3.ZERO, Vec3.ZERO,
            new OperationStackRegion(BlockPos.ZERO, new BlockPos(1, 0, 0)),
            new BlockPos(5, 0, 0)
         ),
         false
      );

      // The whole group is this gesture's repeat unit, so its box holds both copies.
      assertEquals(10, WorkspaceSelectionBounds.extent(WorkspaceSelectionBounds.resolve(stacked), AxisGizmo.Axis.X));
      // The cell box is one copy, so a repeat gesture never moves a placed copy.
      assertEquals(5, WorkspaceSelectionBounds.extent(WorkspaceSelectionBounds.resolveBase(stacked), AxisGizmo.Axis.X));
   }

   @Test
   void wholeBoxHoldsPlacedCopiesWhileBaseBoxHoldsOneCell() {
      List<ClientSelectionPart> parts = List.of(
         cuboidPart(1, BlockPos.ZERO, 5, 1, 12),
         cuboidPart(2, new BlockPos(10, 0, 0), 2, 1, 12)
      );

      AABB whole = WorkspaceSelectionBounds.wholeBox(parts);
      AABB base = WorkspaceSelectionBounds.baseBox(parts);

      assertEquals(24, WorkspaceSelectionBounds.extent(whole, AxisGizmo.Axis.X));
      assertEquals(12, WorkspaceSelectionBounds.extent(base, AxisGizmo.Axis.X));
      assertEquals(2, WorkspaceSelectionBounds.wholeStepCells(whole, base, AxisGizmo.Axis.X));
      // A missing box never divides by zero and never returns a free step.
      assertEquals(1, WorkspaceSelectionBounds.wholeStepCells(null, null, AxisGizmo.Axis.X));
   }

   private static ClientSelectionPart cuboidPart(
      int id, BlockPos origin, int width, int lastRepeat, int stride
   ) {
      OperationSelectionVolume selection = OperationSelectionVolume.cuboid(
         origin, origin.offset(width - 1, 0, 0), origin, origin
      );
      OperationStackRegion repeats = lastRepeat == 0
         ? OperationStackRegion.origin()
         : new OperationStackRegion(BlockPos.ZERO, new BlockPos(lastRepeat, 0, 0));
      return new ClientSelectionPart(
         id, ClientSelectionPart.Source.WORLD, selection, Map.of(),
         new WorkspaceTransform(Vec3.ZERO, Vec3.ZERO, repeats, new BlockPos(stride, 0, 0)),
         false
      );
   }

   @Test
   void unionExtentTreatsSelectedPartsAsOneStackUnit() {
      AABB first = new AABB(0, 0, 0, 3, 1, 1);
      AABB second = new AABB(7, 0, 0, 10, 1, 1);

      AABB group = WorkspaceSelectionBounds.union(first, second);

      assertEquals(10, WorkspaceSelectionBounds.extent(group, AxisGizmo.Axis.X));
   }

   @Test
   void transformedPrismKeepsAnInteractionEnvelopeWhenBlocksCannotResolve() {
      OperationSelectionVolume selection = OperationSelectionVolume.create(
         io.github.fastformer.fastplace.selection.OperationSelectionMode.CONVEX_HULL,
         java.util.List.of(new BlockPos(0, 0, 0), new BlockPos(3, 0, 0), new BlockPos(0, 0, 3)),
         BlockPos.ZERO, BlockPos.ZERO, 0
      );
      ClientSelectionPart part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(),
         new WorkspaceTransform(Vec3.ZERO, new Vec3(0.0, Math.PI * 0.5, 0.0),
            io.github.fastformer.fastplace.selection.OperationStackRegion.origin()), false
      );

      AABB bounds = WorkspaceSelectionBounds.resolve(part);

      assertEquals(true, bounds != null);
      assertEquals(true, bounds.getXsize() > 0.0);
      assertEquals(true, bounds.getZsize() > 0.0);
   }
}
