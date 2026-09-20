package io.github.fastformer.client.render.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.client.interaction.PartInteractionBounds;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorkspaceSelectionOutlineTest {
   @Test
   void airAtSelectionEdgesDoesNotShrinkEditableOutline() {
      var selection = OperationSelectionVolume.cuboid(
         new BlockPos(-8, -3, -5), new BlockPos(10, 7, 9),
         BlockPos.ZERO, new BlockPos(1, 1, 1)
      );
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
         selection, Map.of(), WorkspaceTransform.IDENTITY, false);
      assertEquals(selection.bounds(), PartInteractionBounds.resolve(part));
   }

   @Test
   void transformedStructureKeepsItsResultOutline() {
      var selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(10, 7, 9), BlockPos.ZERO, BlockPos.ZERO
      );
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
         selection, Map.of(), WorkspaceTransform.IDENTITY, false)
         .withTranslation(new Vec3(20, 0, 0));
      assertEquals(new AABB(20, 0, 0, 31, 8, 10),
         PartInteractionBounds.resolve(part));
   }

   @Test
   void axisAlignedFallbackIncludesScaleAndRepeatExtent() {
      var selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(2, 1, 1), BlockPos.ZERO, BlockPos.ZERO
      );
      var transform = WorkspaceTransform.IDENTITY
         .withScale(io.github.fastformer.fastplace.geometry.AxisGizmo.Axis.X, 2.0)
         .withRepeats(new io.github.fastformer.fastplace.selection.OperationStackRegion(
            BlockPos.ZERO, new BlockPos(1, 0, 0)), BlockPos.ZERO);
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
         selection, Map.of(), transform, false);

      assertEquals(new AABB(-1.5, 0, 0, 10.5, 2, 2), PartInteractionBounds.resolve(part));
   }

   @Test
   void rotatedCuboidFallbackRetainsAWorldBounds() {
      var selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(2, 0, 0), BlockPos.ZERO, BlockPos.ZERO
      );
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
         selection, Map.of(), WorkspaceTransform.IDENTITY.withRotation(
            new Vec3(0.0, Math.PI * 0.5, 0.0)
         ), false);

      AABB bounds = PartInteractionBounds.resolve(part);
      assertEquals(1.0, bounds.getXsize(), 1.0E-9);
      assertEquals(1.0, bounds.getYsize(), 1.0E-9);
      assertEquals(3.0, bounds.getZsize(), 1.0E-9);
   }

   @Test
   void rotatedNonCuboidFallbackReachesTheRendererBoundary() {
      var selection = OperationSelectionVolume.create(
         io.github.fastformer.fastplace.selection.OperationSelectionMode.CONVEX_HULL,
         java.util.List.of(BlockPos.ZERO, new BlockPos(3, 0, 0), new BlockPos(0, 0, 3)),
         BlockPos.ZERO, BlockPos.ZERO, 0
      );
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
         selection, Map.of(), WorkspaceTransform.IDENTITY.withRotation(
            new Vec3(0.0, Math.PI * 0.25, 0.0)
         ), false);

      AABB bounds = PartInteractionBounds.resolve(part);

      assertEquals(true, bounds != null);
      assertEquals(true, bounds.getXsize() > 0.0);
      assertEquals(true, bounds.getZsize() > 0.0);
   }
}
