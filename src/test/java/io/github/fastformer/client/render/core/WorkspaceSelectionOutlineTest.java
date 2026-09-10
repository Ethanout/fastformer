package io.github.fastformer.client.render.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.OperationSelectionVolume;
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
      AABB occupied = new AABB(0, 0, 0, 2, 2, 2);

      assertEquals(selection.bounds(), WorkspaceInteractionResolver.outlineBounds(part, occupied));
      assertEquals(WorkspaceInteractionResolver.selectionBounds(part),
         WorkspaceInteractionResolver.outlineBounds(part, occupied));
   }

   @Test
   void transformedStructureKeepsItsResultOutline() {
      var selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(10, 7, 9), BlockPos.ZERO, BlockPos.ZERO
      );
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
         selection, Map.of(), WorkspaceTransform.IDENTITY, false)
         .withTranslation(new Vec3(20, 0, 0));
      AABB occupied = new AABB(20, 0, 0, 22, 2, 2);

      assertEquals(occupied, WorkspaceInteractionResolver.outlineBounds(part, occupied));
   }
}
