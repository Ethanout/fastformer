package io.github.fastformer.fastplace.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class DimensionSessionStoreTest {
   @Test
   void parkAndTakeReturnTheSameSessionForOneDimension() {
      DimensionSessionStore<String> store = new DimensionSessionStore<>();
      UUID owner = UUID.randomUUID();

      store.park(owner, Level.OVERWORLD, "overworld");

      assertSame("overworld", store.peek(owner, Level.OVERWORLD));
      assertEquals(1, store.dimensionCount(owner));
      assertSame("overworld", store.take(owner, Level.OVERWORLD));
      assertNull(store.peek(owner, Level.OVERWORLD));
      assertEquals(0, store.dimensionCount(owner));
   }

   @Test
   void dimensionsKeepSeparateSessions() {
      DimensionSessionStore<String> store = new DimensionSessionStore<>();
      UUID owner = UUID.randomUUID();

      store.park(owner, Level.OVERWORLD, "overworld");
      store.park(owner, Level.NETHER, "nether");
      store.park(owner, Level.END, "end");

      assertEquals(3, store.dimensionCount(owner));
      assertSame("nether", store.take(owner, Level.NETHER));
      assertSame("overworld", store.take(owner, Level.OVERWORLD));
      assertSame("end", store.take(owner, Level.END));
   }

   @Test
   void playersKeepSeparateSessions() {
      DimensionSessionStore<String> store = new DimensionSessionStore<>();
      UUID first = UUID.randomUUID();
      UUID second = UUID.randomUUID();

      store.park(first, Level.OVERWORLD, "first");
      store.park(second, Level.OVERWORLD, "second");

      assertSame("first", store.take(first, Level.OVERWORLD));
      assertSame("second", store.take(second, Level.OVERWORLD));
   }

   @Test
   void onePlayerCannotTakeAnotherPlayerSession() {
      DimensionSessionStore<String> store = new DimensionSessionStore<>();
      UUID owner = UUID.randomUUID();
      UUID stranger = UUID.randomUUID();

      store.park(owner, Level.OVERWORLD, "overworld");

      assertNull(store.take(stranger, Level.OVERWORLD));
      assertSame("overworld", store.peek(owner, Level.OVERWORLD));
   }

   @Test
   void forgetAndClearDropEveryStoredSession() {
      DimensionSessionStore<String> store = new DimensionSessionStore<>();
      UUID owner = UUID.randomUUID();

      store.park(owner, Level.OVERWORLD, "overworld");
      store.park(owner, Level.NETHER, "nether");
      store.forget(owner);

      assertEquals(0, store.dimensionCount(owner));

      store.park(owner, Level.OVERWORLD, "overworld");
      store.clear();

      assertNull(store.peek(owner, Level.OVERWORLD));
   }

   @Test
   void aMissingKeyOrOwnerIsIgnored() {
      DimensionSessionStore<String> store = new DimensionSessionStore<>();
      UUID owner = UUID.randomUUID();

      store.park(null, Level.OVERWORLD, "ignored");
      store.park(owner, null, "ignored");
      store.park(owner, Level.OVERWORLD, null);

      assertEquals(0, store.dimensionCount(owner));
      assertNull(store.take(null, Level.OVERWORLD));
      assertNull(store.take(owner, null));
   }
}
