package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerInputDispatcherPlacementActionTest {
   private final UUID owner = UUID.randomUUID();

   @AfterEach
   void clearState() {
      ServerInputDispatcher.clearAllPlacementActions();
   }

   @Test
   void acceptsOnlyStrictlyIncreasingRequestIds() {
      assertTrue(ServerInputDispatcher.acceptPlacementAction(owner, 1L));
      assertFalse(ServerInputDispatcher.acceptPlacementAction(owner, 1L));
      assertFalse(ServerInputDispatcher.acceptPlacementAction(owner, 0L));
      assertFalse(ServerInputDispatcher.acceptPlacementAction(owner, 0L));
      assertTrue(ServerInputDispatcher.acceptPlacementAction(owner, 2L));
      assertFalse(ServerInputDispatcher.acceptPlacementAction(owner, 1L));
   }

   @Test
   void clearingAnOwnerAllowsAFreshConnectionSequence() {
      assertTrue(ServerInputDispatcher.acceptPlacementAction(owner, 8L));
      ServerInputDispatcher.clearPlacementActions(owner);
      assertTrue(ServerInputDispatcher.acceptPlacementAction(owner, 1L));
   }
}
