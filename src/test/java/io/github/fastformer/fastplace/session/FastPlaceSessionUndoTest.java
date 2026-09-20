package io.github.fastformer.fastplace.session;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class FastPlaceSessionUndoTest {
   private static final List<BlockPos> BASE = List.of(BlockPos.ZERO, new BlockPos(3, 0, 0), new BlockPos(0, 0, 3));

   @Test
   void ordinaryStagesRemoveOnePointUntilEmpty() {
      FastPlaceSession session = sessionWithBase();
      assertTrue(session.undoStep());
      assertEquals(BASE.subList(0, 2), session.points());
      assertTrue(session.undoStep());
      assertEquals(BASE.subList(0, 1), session.points());
      assertFalse(session.undoStep());
      assertEquals(List.of(), session.points());
      assertFalse(session.undoStep());
   }

   @Test
   void closedBaseUndoRemovesAPointAndReopensTheFace() {
      FastPlaceSession session = sessionWithBase();
      session.closePolygon();
      assertTrue(session.undoStep());
      assertEquals(BASE.subList(0, 2), session.points());
      assertFalse(session.polygonClosed());
      assertFalse(session.polygonHeightConfirmed());
   }

   @Test
   void heightUndoPreservesBaseThenNextUndoRemovesABasePoint() {
      FastPlaceSession session = sessionWithBase();
      session.closePolygon();
      session.addPoint(new BlockPos(0, 3, 0), Vec3.ZERO, new Vec3(1, 0, 0));
      session.confirmPolygonHeight();
      assertTrue(session.undoStep());
      assertEquals(BASE, session.points());
      assertTrue(session.polygonClosed());
      assertFalse(session.polygonHeightConfirmed());
      assertTrue(session.undoStep());
      assertEquals(BASE.subList(0, 2), session.points());
      assertFalse(session.polygonClosed());
   }

   @Test
   void undoClearsCandidateOffsetsAndRemovedMeasurementAnchor() {
      FastPlaceSession session = new FastPlaceSession();
      session.addPoint(BlockPos.ZERO, Vec3.ZERO, new Vec3(0, 0, 1));
      BlockPos last = new BlockPos(5, 0, 0);
      session.addPoint(last, new Vec3(5.5, 0.5, -5), new Vec3(0, 0, 1));
      assertEquals(last, session.perpendicularAnchor());
      session.setFreeScrollOffset(new BlockPos(1, 2, 3));
      session.setFaceBaseOffset(new Vec3(0, 1, 0), 4);
      session.setVolumeBaseOffset(new Vec3(3, 4, 5));

      assertTrue(session.undoStep());

      assertNull(session.perpendicularAnchor());
      assertEquals(BlockPos.ZERO, session.freeScrollOffset());
      assertEquals(Vec3.ZERO, session.faceBaseOffset());
      assertEquals(Vec3.ZERO, session.volumeBaseOffset());
   }

   private static FastPlaceSession sessionWithBase() {
      FastPlaceSession session = new FastPlaceSession();
      BASE.forEach(point -> session.addPoint(point, Vec3.ZERO, new Vec3(1, 0, 0)));
      return session;
   }
}
