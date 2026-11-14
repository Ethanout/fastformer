package io.github.fastformer.server.intent.selection;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.selection.OperationPointDragConstraint;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SelectionIntentsTest {
   @Test
   void pointIntentsFreezeMutableCoordinates() {
      var point = new BlockPos.MutableBlockPos(1, 2, 3);
      var expand = new SelectionIntents.Expand(point);
      var insert = new SelectionIntents.InsertPoint(1, point);
      var drag = new SelectionIntents.DragPoint(0, point, OperationPointDragConstraint.FREE, false);
      point.set(9, 9, 9);
      assertEquals(new BlockPos(1, 2, 3), expand.point());
      assertEquals(expand.point(), insert.point());
      assertEquals(expand.point(), drag.target());
   }

   @Test
   void requiredEditInputsFailAtConstruction() {
      assertThrows(NullPointerException.class, () -> new SelectionIntents.Expand(null));
      assertThrows(NullPointerException.class, () -> new SelectionIntents.InsertPoint(0, null));
      assertThrows(NullPointerException.class, () -> new SelectionIntents.DragPoint(0, BlockPos.ZERO, null, true));
   }
}
