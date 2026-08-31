package io.github.fastformer.client.operation.transform;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.Map;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class RepeatDragQuantizerTest {
   @Test
   void waitsForAWholeStructureBeforeAddingARepeat() {
      assertEquals(0, RepeatDragQuantizer.copiesForOffset(5.99, 6));
      assertEquals(1, RepeatDragQuantizer.copiesForOffset(6.0, 6));
      assertEquals(1, RepeatDragQuantizer.copiesForOffset(11.99, 6));
      assertEquals(2, RepeatDragQuantizer.copiesForOffset(12.0, 6));
   }

   @Test
   void reverseDragUsesTheSameWholeStructureThreshold() {
      assertEquals(0, RepeatDragQuantizer.copiesForOffset(-5.99, 6));
      assertEquals(-1, RepeatDragQuantizer.copiesForOffset(-6.0, 6));
      assertEquals(-2, RepeatDragQuantizer.copiesForOffset(-12.0, 6));
   }

   @Test
   void singleBlockStructureRespondsBeforeAFullBlockOfTravel() {
      assertEquals(0, RepeatDragQuantizer.copiesForOffset(0.49, 1));
      assertEquals(1, RepeatDragQuantizer.copiesForOffset(0.5, 1));
      assertEquals(-1, RepeatDragQuantizer.copiesForOffset(-0.5, 1));
   }

   @Test
   void cuboidUsesSelectionExtentEvenWhenItsEdgeContainsNoBlocks() {
      OperationSelectionVolume selection = new OperationSelectionVolume(
         OperationSelectionMode.CUBOID, new AABB(2, 3, 4, 8, 5, 7), null, java.util.List.of(), 0, null, null
      );
      ClientSelectionPart part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );

      assertEquals(6, RepeatDragQuantizer.structureExtent(part, AxisGizmo.Axis.X));
      assertEquals(2, RepeatDragQuantizer.structureExtent(part, AxisGizmo.Axis.Y));
      assertEquals(3, RepeatDragQuantizer.structureExtent(part, AxisGizmo.Axis.Z));
   }
}
