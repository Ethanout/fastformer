package io.github.fastformer.server.task.block;

import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.common.world.memory.MemoryAdmission;
import io.github.fastformer.common.world.memory.WorldOperationMemory;
import io.github.fastformer.server.task.MemoryReservationAttempt;
import io.github.fastformer.server.worldwrite.snapshot.BlockSnapshotReader;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.worldwrite.snapshot.SnapshotMemoryRules;
import io.github.fastformer.server.worldwrite.transaction.WorldChangeTransaction;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Captures a composed target batch once, retaining cursor progress across budget contention. */
final class WorkspaceSnapshotCapture {
   enum Step { ADVANCED, RETRY, REJECTED, INVALID_TARGET, SNAPSHOT_UNAVAILABLE }
   private Map<BlockPos, BlockSnapshot> desired;
   private Iterator<Map.Entry<BlockPos, BlockSnapshot>> cursor;
   private boolean complete;
   private long nbtReserve;
   private BlockPos failedPosition;

   /** The caller transfers ownership of the composed, support-first target map. */
   WorkspaceSnapshotCapture(Map<BlockPos, BlockSnapshot> desired) {
      this.desired = Collections.unmodifiableMap(desired);
      cursor = this.desired.entrySet().iterator();
   }

   Map<BlockPos, BlockSnapshot> desired() { return desired; }
   boolean complete() { return complete; }
   long nbtReserve() { return nbtReserve; }
   BlockPos failedPosition() { return failedPosition; }

   Step next(ServerLevel level, WorldChangeTransaction transaction, BlockSnapshotReader snapshots,
      BiPredicate<BlockPos, BlockSnapshot> valid, Function<MemoryAdmission, MemoryReservationAttempt> reserve,
      Runnable onCaptured) {
      Step admission = admit(reserve, WorldOperationMemory.snapshotAdmission(desired.size(), nbtReserve));
      if (admission != Step.ADVANCED) return admission;
      if (cursor != null && cursor.hasNext()) {
         Map.Entry<BlockPos, BlockSnapshot> entry = cursor.next();
         if (!valid.test(entry.getKey(), entry.getValue())) {
            failedPosition = entry.getKey();
            return Step.INVALID_TARGET;
         }
         Optional<ReversibleBlockSnapshot> read = snapshots.capture(level, entry.getKey());
         if (read.isEmpty()) { failedPosition = entry.getKey(); return Step.SNAPSHOT_UNAVAILABLE; }
         ReversibleBlockSnapshot snapshot = read.orElseThrow();
         onCaptured.run();
         transaction.recordExpected(entry.getKey(), snapshot);
         nbtReserve = WorldOperationMemory.saturatingAdd(nbtReserve, SnapshotMemoryRules.nbtReserve(snapshot));
         admission = admit(reserve, WorldOperationMemory.snapshotAdmission(desired.size(), nbtReserve));
         if (admission != Step.ADVANCED) return admission;
      }
      if (cursor == null || !cursor.hasNext()) {
         complete = true;
         cursor = null;
         return admit(reserve, WorldOperationMemory.journalAdmission(desired.size(), nbtReserve));
      }
      return Step.ADVANCED;
   }

   /** Drops source maps after writes; the transaction retains the committed before/after sides. */
   void release() { desired = Map.of(); cursor = null; }

   private static Step admit(Function<MemoryAdmission, MemoryReservationAttempt> reserve, MemoryAdmission estimate) {
      return switch (reserve.apply(estimate)) {
         case ACQUIRED -> Step.ADVANCED;
         case RETRY -> Step.RETRY;
         case REJECTED -> Step.REJECTED;
      };
   }
}
