package io.github.fastformer.fastplace.world;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldTaskFeatureTest {
   @AfterEach
   void clearFeatureState() {
      WorldTaskFeature.clear();
      PersistentRecoveryJournal.resetWriteGateForTest();
   }

   @Test
   void offlineProgressKeepsOnlyTheLatestActionBar() {
      UUID owner = UUID.randomUUID();
      Component first = Component.literal("first");
      Component latest = Component.literal("latest");

      WorldTaskFeature.deferActionBar(owner, first);
      WorldTaskFeature.deferActionBar(owner, latest);

      assertEquals(latest, WorldTaskFeature.pendingActionBarForTest(owner));
   }

   @Test
   void offlineResultQueueIsBounded() {
      UUID owner = UUID.randomUUID();
      for (int index = 0; index < 10; index++) {
         WorldTaskFeature.deferChat(owner, Component.literal(Integer.toString(index)));
      }

      assertEquals(4, WorldTaskFeature.pendingChatCountForTest(owner));
   }

   @Test
   void schedulerPhaseFailureStopsTheCallerFromAdvancing() {
      assertTrue(WorldTaskFeature.tickSafely("test-success", () -> {
      }));
      assertFalse(WorldTaskFeature.tickSafely("test-failure", () -> {
         throw new IllegalStateException("expected test failure");
      }));
      assertFalse(PersistentRecoveryJournal.writesAllowed());
   }
}
