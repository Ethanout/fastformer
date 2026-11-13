package io.github.fastformer.server.task;

import com.mojang.logging.LogUtils;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.recovery.RecoverySystem;
import io.github.fastformer.server.recovery.gate.RecoveryWriteGate;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.session.SessionSystem;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** Advances server-owned writers in recovery-first order. Owns no player or task state. */
public final class WorldTaskScheduler {
   private static final Logger LOGGER = LogUtils.getLogger();

   private WorldTaskScheduler() {}

   public static void tick(MinecraftServer server) {
      RecoveryWriteGate gate = RecoverySystem.of(server).writeGate();
      if (!tickSafely(gate, "history/recovery", () -> HistorySystem.of(server).tickWorld(server))) return;
      // A closed gate must transfer queued writers instead of waiting for a lease.
      // Both transfers run independently so one failure does not skip the other.
      if (gate.writesAllowed()) {
         if (!tickSafely(gate, "placement", () -> SessionSystem.of(server).placementTasks().tickWorld(server, HistorySystem.of(server)))) return;
      } else {
         tickSafely(gate, "placement-handover", () -> SessionSystem.of(server).placementTasks().handOverBlockedTasks(server, HistorySystem.of(server)));
      }
      if (gate.writesAllowed()) {
         tickSafely(gate, "operation", () -> OperationSystem.of(server).tickWorld(server, HistorySystem.of(server)));
      } else {
         tickSafely(gate, "operation-handover", () -> OperationSystem.of(server).handOverBlockedTasks(server, HistorySystem.of(server)));
      }
      tickSafely(gate, "submission-ledger", () -> SessionSystem.of(server).submissions().tick());
   }

   static boolean tickSafely(RecoveryWriteGate gate, String kind, Runnable tick) {
      try {
         tick.run();
         return true;
      } catch (RuntimeException | OutOfMemoryError failure) {
         gate.blockNewWrites();
         LOGGER.error("FastFormer {} world task scheduler failed; journals and leases were retained", kind, failure);
         return false;
      }
   }
}
