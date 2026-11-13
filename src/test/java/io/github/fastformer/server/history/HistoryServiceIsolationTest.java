package io.github.fastformer.server.history;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.core.gate.Gate;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class HistoryServiceIsolationTest {
   @Test
   void samePlayerHasIndependentStateInEachService() {
      UUID player = UUID.randomUUID();
      try (HistoryService first = service(); HistoryService second = service()) {
         first.ownerState(player).historyLimit = 7;
         second.ownerState(player).historyLimit = 15;
         assertNotSame(first.ownerState(player), second.ownerState(player));
         first.clear();
         assertEquals(15, second.ownerState(player).historyLimit);
         assertNotEquals(7, first.ownerState(player).historyLimit);
      }
   }

   @Test
   void closedServiceCannotRecreateOwnerOrAcceptRepositoryWrites() {
      HistoryService history = service();
      UUID player = UUID.randomUUID();
      history.ownerState(player);
      history.close();
      history.close();
      assertEquals(0, history.ownerCount());
      assertThrows(IllegalStateException.class, () -> history.ownerState(player));
      assertThrows(CompletionException.class, () -> history.repository().loadSnapshot(player, 1, 1).join());
      history.clear();
      assertThrows(IllegalStateException.class, () -> history.ownerState(player));
   }

   private static HistoryService service() {
      Gate<net.minecraft.server.level.ServerPlayer> gate = Gate.require("available", ignored -> true);
      return new HistoryService(gate, gate, new NoDiskHistoryRepository());
   }
}
