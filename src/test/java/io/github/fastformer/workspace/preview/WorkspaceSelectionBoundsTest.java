package io.github.fastformer.workspace.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
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
   void shortcutPivotsDoNotAccumulateRoundingAcrossMixedParityTurns() {
      for (int origin : new int[] {-7, 0, 8}) for (int width : new int[] {1, 2, 3}) for (int depth : new int[] {1, 2, 3}) {
         var blocks = new java.util.LinkedHashMap<BlockPos, String>();
         for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            blocks.put(new BlockPos(origin + x, 0, origin + z), "block");
         }
         var selection = OperationSelectionVolume.cuboid(new BlockPos(origin, 0, origin),
            new BlockPos(origin + width - 1, 0, origin + depth - 1), null, null);
         for (double shift : new double[] {-7, -0.25, 0, 0.25, 10}) {
            WorkspaceTransform transform = WorkspaceTransform.IDENTITY.withTranslation(new Vec3(shift, 3, -7));
            // Use position-only values to exercise the same pipeline without a block registry.
            var before = WorkspacePreviewComposer.resolveValues(blocks, transform);
            Vec3 pivot = null;
            for (int turn = 0; turn < 4; turn++) {
               // The empty snapshots are irrelevant to bounds: a cuboid supplies the same envelope.
               var part = new ClientSelectionPart(1, ClientSelectionPart.Source.CLIPBOARD, selection,
                  java.util.Map.of(), transform, false);
               Vec3 currentPivot = WorkspaceSelectionBounds.continuousWholeBox(List.of(part)).getCenter();
               if (pivot == null) pivot = currentPivot;
               assertEquals(pivot, currentPivot, "shortcut reused a snapped centre");
               transform = transform.withSymmetry(transform.symmetry().then(
                  io.github.fastformer.workspace.model.WorkspaceSymmetry.quarterTurnY(),
                  currentPivot.subtract(transform.translation())));
            }
            assertEquals(before, WorkspacePreviewComposer.resolveValues(blocks, transform),
               "origin=" + origin + ", size=" + width + "x" + depth + ", shift=" + shift);
         }
      }
   }

   @Test
   void quarterTurnFramesMatchVoxelExtentsAcrossMixedParityAndNegativeCoordinates() {
      for (int origin : new int[] {-7, 0, 8}) for (int width : new int[] {2, 3}) for (int depth : new int[] {2, 3}) {
         var blocks = new java.util.LinkedHashMap<BlockPos, String>();
         for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) {
            blocks.put(new BlockPos(origin + x, 0, origin + z), "block");
         }
         AABB source = new AABB(origin, 0, origin, origin + width, 1, origin + depth);
         var symmetry = io.github.fastformer.workspace.model.WorkspaceSymmetry.IDENTITY;
         for (int turn = 0; turn < 4; turn++) {
            symmetry = symmetry.then(io.github.fastformer.workspace.model.WorkspaceSymmetry.quarterTurnY(), source.getCenter());
            var transform = WorkspaceTransform.IDENTITY.withSymmetry(symmetry);
            var occupied = io.github.fastformer.workspace.selection.OccupiedBlockBounds
               .from(WorkspacePreviewComposer.resolveValues(blocks, transform).keySet()).orElseThrow().aabb();
            assertEquals(occupied, WorkspaceSelectionBounds.transformedBox(source, transform, null),
               "origin=" + origin + ", size=" + width + "x" + depth + ", turn=" + turn);
         }
      }
   }

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
         AABB occupied = io.github.fastformer.workspace.selection.OccupiedBlockBounds
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

      // The displayed box holds both copies. The base box keeps the original unit.
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
