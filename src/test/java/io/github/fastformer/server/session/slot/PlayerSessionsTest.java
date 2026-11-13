package io.github.fastformer.server.session.slot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PlayerSessionsTest {
   @Test
   void dimensionsAndPlayersRetainIndependentSessions() {
      var sessions = new PlayerSessions<Object>();
      UUID first = UUID.randomUUID();
      UUID second = UUID.randomUUID();
      Object overworld = new Object();
      Object nether = new Object();
      Object stranger = new Object();
      sessions.activate(first, overworld);
      sessions.park(first, Level.OVERWORLD);
      assertFalse(sessions.hasActive(first));
      sessions.activate(first, nether);
      sessions.activate(second, stranger);
      sessions.park(first, Level.NETHER);
      sessions.restore(first, Level.OVERWORLD);
      assertSame(overworld, sessions.active(first));
      assertSame(nether, sessions.parked(first, Level.NETHER));
      assertSame(stranger, sessions.active(second));
      assertNull(sessions.parked(second, Level.NETHER));
   }

   @Test
   void restoreConflictDoesNotConsumeOrOverwriteEitherSession() {
      var slot = new PlayerSessionSlot<Object>();
      Object parked = new Object();
      Object active = new Object();
      slot.activate(parked);
      slot.park(Level.OVERWORLD);
      slot.activate(active);
      slot.restore(Level.OVERWORLD);
      assertSame(active, slot.active());
      assertSame(parked, slot.parked(Level.OVERWORLD));
      slot.end();
      slot.restore(Level.OVERWORLD);
      assertSame(parked, slot.active());
      assertEquals(0, slot.parkedCount());
   }

   @Test
   void endingLastSessionPrunesTheOwnerAndDiscardDropsParkedWork() {
      var sessions = new PlayerSessions<Object>();
      UUID owner = UUID.randomUUID();
      Object active = sessions.getOrCreate(owner, Object::new);
      assertSame(active, sessions.getOrCreate(owner, () -> { throw new AssertionError("already active"); }));
      sessions.park(owner, Level.OVERWORLD);
      assertNull(sessions.end(owner));
      assertEquals(1, sessions.size());
      sessions.restore(owner, Level.OVERWORLD);
      assertSame(active, sessions.end(owner));
      assertEquals(0, sessions.size());
      sessions.activate(owner, active);
      sessions.park(owner, Level.END);
      sessions.discard(owner);
      assertNull(sessions.parked(owner, Level.END));
      assertEquals(0, sessions.size());
   }

   @Test
   void missingDimensionAndUnknownOwnerDoNotChangeLiveWork() {
      var sessions = new PlayerSessions<Object>();
      UUID owner = UUID.randomUUID();
      Object active = new Object();
      sessions.activate(owner, active);
      sessions.park(owner, null);
      sessions.restore(owner, null);
      sessions.park(UUID.randomUUID(), Level.OVERWORLD);
      sessions.restore(UUID.randomUUID(), Level.NETHER);
      assertSame(active, sessions.active(owner));
      assertEquals(1, sessions.size());
      sessions.clear();
      assertEquals(0, sessions.size());
      assertTrue(new PlayerSessionSlot<>().empty());
   }
}
