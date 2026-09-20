package io.github.fastformer.fastplace.session;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class GeometrySessionUndoTest {
   @Test
   void undoRemovesLastPointEvenWhenEarlierPointIsWorldOrigin() {
      var session = new GeometrySession();
      session.addPoint(BlockPos.ZERO);
      session.addPoint(new BlockPos(4, 0, 0));
      session.addPoint(new BlockPos(4, 0, 4));
      assertTrue(session.undoStep());
      assertEquals(List.of(BlockPos.ZERO, new BlockPos(4, 0, 0)), session.points());
      assertTrue(session.undoStep());
      assertEquals(List.of(BlockPos.ZERO), session.points());
      assertTrue(session.undoStep());
      assertFalse(session.undoStep());
   }

   @Test
   void explicitPointRemovalStillCanRemoveWorldOrigin() {
      var session = new GeometrySession();
      session.addPoint(BlockPos.ZERO);
      session.addPoint(new BlockPos(4, 0, 0));
      assertTrue(session.removeOrUndo(BlockPos.ZERO));
      assertEquals(List.of(new BlockPos(4, 0, 0)), session.points());
   }
}
