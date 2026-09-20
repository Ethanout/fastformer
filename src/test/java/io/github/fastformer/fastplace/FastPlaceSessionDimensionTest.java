package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.session.FastPlaceSession;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * A dimension change must keep the selection of the dimension the player left
 * and must never offer those points to another dimension.
 */
class FastPlaceSessionDimensionTest {
   @Test
   void aDimensionChangeParksTheOldSelectionAndRestoresItOnReturn() {
      UUID owner = UUID.randomUUID();
      FastPlaceSession overworld = sessionWithPoint(new BlockPos(4, 5, 6));
      try {
         FastPlaceManager.putSessionForTest(owner, overworld);
         assertTrue(FastPlaceManager.activeSessionForTest(owner));

         FastPlaceManager.parkDimensionSession(owner, Level.OVERWORLD);

         assertFalse(FastPlaceManager.activeSessionForTest(owner));
         assertTrue(FastPlaceManager.parkedSessionForTest(owner, Level.OVERWORLD));
         // The target dimension has no selection of its own yet.
         assertFalse(FastPlaceManager.parkedSessionForTest(owner, Level.NETHER));

         FastPlaceManager.restoreDimensionSession(owner, Level.NETHER);
         assertFalse(FastPlaceManager.activeSessionForTest(owner));

         FastPlaceManager.restoreDimensionSession(owner, Level.OVERWORLD);
         assertTrue(FastPlaceManager.activeSessionForTest(owner));
         assertFalse(FastPlaceManager.parkedSessionForTest(owner, Level.OVERWORLD));
      } finally {
         FastPlaceManager.parkDimensionSession(owner, Level.OVERWORLD);
         FastPlaceManager.parkDimensionSession(owner, Level.NETHER);
      }
   }

   @Test
   void eachDimensionKeepsItsOwnSelection() {
      UUID owner = UUID.randomUUID();
      FastPlaceSession overworld = sessionWithPoint(new BlockPos(1, 0, 0));
      FastPlaceSession nether = sessionWithPoint(new BlockPos(0, 0, 1));
      try {
         FastPlaceManager.putSessionForTest(owner, overworld);
         FastPlaceManager.parkDimensionSession(owner, Level.OVERWORLD);
         FastPlaceManager.putSessionForTest(owner, nether);

         // The nether session is active, and the overworld points are stored
         // apart instead of being merged into the new selection.
         assertTrue(FastPlaceManager.activeSessionForTest(owner));
         assertTrue(FastPlaceManager.parkedSessionForTest(owner, Level.OVERWORLD));
         assertFalse(FastPlaceManager.parkedSessionForTest(owner, Level.NETHER));

         FastPlaceManager.parkDimensionSession(owner, Level.NETHER);
         FastPlaceManager.restoreDimensionSession(owner, Level.OVERWORLD);

         assertTrue(FastPlaceManager.activeSessionForTest(owner));
         assertTrue(FastPlaceManager.parkedSessionForTest(owner, Level.NETHER));
      } finally {
         FastPlaceManager.parkDimensionSession(owner, Level.OVERWORLD);
         FastPlaceManager.parkDimensionSession(owner, Level.NETHER);
      }
   }

   @Test
   void aRestoredSessionNeverReplacesTheActiveSessionOfTheTargetDimension() {
      UUID owner = UUID.randomUUID();
      try {
         FastPlaceManager.parkDimensionSession(owner, Level.OVERWORLD);
         FastPlaceManager.putSessionForTest(owner, sessionWithPoint(new BlockPos(9, 9, 9)));
         FastPlaceManager.restoreDimensionSession(owner, Level.OVERWORLD);
         // No parked session exists for the overworld here, so the live one stays.
         assertTrue(FastPlaceManager.activeSessionForTest(owner));
      } finally {
         FastPlaceManager.parkDimensionSession(owner, Level.OVERWORLD);
         FastPlaceManager.parkDimensionSession(owner, Level.NETHER);
      }
   }

   @Test
   void parkingAnUnknownDimensionChangesNothing() {
      UUID owner = UUID.randomUUID();
      FastPlaceManager.putSessionForTest(owner, sessionWithPoint(new BlockPos(0, 0, 0)));
      try {
         FastPlaceManager.parkDimensionSession(owner, null);
         assertTrue(FastPlaceManager.activeSessionForTest(owner));
         assertEquals(1, FastPlaceManager.sessionPointsForTest(owner).size());
      } finally {
         FastPlaceManager.parkDimensionSession(owner, Level.OVERWORLD);
      }
   }

   private static FastPlaceSession sessionWithPoint(BlockPos point) {
      FastPlaceSession session = new FastPlaceSession();
      session.addPoint(point, Vec3.ZERO, new Vec3(0.0, 0.0, 1.0));
      return session;
   }
}
