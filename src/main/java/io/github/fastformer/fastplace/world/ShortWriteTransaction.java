package io.github.fastformer.fastplace.world;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import io.github.fastformer.fastplace.FastPlaceMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Applies a small set of block changes as one short world transaction.
 *
 * <p>A short transaction obeys the same write contract as a large task: it
 * takes the dimension lease before the first write, captures every target, and
 * either records the change in history or restores the captured state. A
 * restore that does not complete is never discarded: the transaction hands the
 * only recovery data to the existing recovery task and keeps the lease, so the
 * world keeps a single writer and the failed positions stay recoverable.</p>
 */
public final class ShortWriteTransaction {
   private static final Logger LOGGER = LogUtils.getLogger();

   /** The result of one short world transaction. */
   public enum Outcome {
      /** The caller refused before any lease or write, for example no change is possible. */
      NOT_APPLICABLE,
      /** Another transaction owns the dimension, so nothing was attempted. */
      REJECTED_BUSY,
      /** Every change was written and recorded. */
      APPLIED,
      /** The transaction failed and the live world is back at the captured state. */
      RESTORED,
      /** The restore did not complete; a recovery task owns the snapshots and the lease. */
      RECOVERY_PENDING,
      /**
       * The restore did not complete and no recovery owner accepted the
       * snapshots. The lease stays locked and the caller owns the capture.
       */
      RECOVERY_BLOCKED;

      /**
       * Reports whether the short transaction consumed the interaction.
       *
       * <p>Only {@link #NOT_APPLICABLE} and {@link #REJECTED_BUSY} leave the
       * target untouched, so only those two results let the caller continue
       * with the default game interaction.</p>
       */
      public boolean consumedInteraction() {
         return this == APPLIED || this == RESTORED || this == RECOVERY_PENDING || this == RECOVERY_BLOCKED;
      }
   }

   private ShortWriteTransaction() {
   }

   /**
    * Writes every change, or restores every captured target.
    *
    * @param writeFlags block update flags for the new states
    * @param restoreFlags block update flags for a restore of captured states
    */
   public static Outcome apply(
      ServerPlayer player,
      ServerLevel level,
      Map<BlockPos, BlockState> changes,
      int writeFlags,
      int restoreFlags
   ) {
      if (player == null || level == null || changes == null || changes.isEmpty()) {
         return Outcome.NOT_APPLICABLE;
      }
      WorldTaskContext context = new WorldTaskContext(player.getServer(), player.getUUID());
      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(context.server(), level.dimension(), context.owner());
      if (lease == null) {
         return Outcome.REJECTED_BUSY;
      }
      ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>();
      Map<BlockPos, ReversibleBlockSnapshot> after = new LinkedHashMap<>();
      try {
         for (BlockPos pos : changes.keySet()) {
            Optional<ReversibleBlockSnapshot> snapshot = ReversibleBlockSnapshot.capture(level, pos);
            if (snapshot.isEmpty()) {
               return fail(level, context, lease, before, after, restoreFlags, "before-snapshot");
            }
            before.addFirst(snapshot.orElseThrow());
         }
         for (Map.Entry<BlockPos, BlockState> entry : changes.entrySet()) {
            if (!WorldWriteSideEffectGuard.setBlock(level, entry.getKey(), entry.getValue(), writeFlags)) {
               return fail(level, context, lease, before, after, restoreFlags, "write");
            }
            Optional<ReversibleBlockSnapshot> snapshot = ReversibleBlockSnapshot.capture(level, entry.getKey());
            if (snapshot.isEmpty()) {
               return fail(level, context, lease, before, after, restoreFlags, "after-snapshot");
            }
            after.put(entry.getKey(), snapshot.orElseThrow());
         }
         if (!WorldHistoryManager.record(player, level, before, after)) {
            return fail(level, context, lease, before, after, restoreFlags, "history-record");
         }
         WorldWriteCoordinator.release(lease);
         return Outcome.APPLIED;
      } catch (RuntimeException | Error exception) {
         // A block callback can throw after the world already changed. The live
         // state is unknown at that point, so restore from the captured
         // snapshots instead of releasing the lease with a partial write.
         LOGGER.warn(
            "FastFormer short world transaction failed in {} for {}; restoring {} captured positions",
            level.dimension().location(),
            context.owner(),
            exception
         );
         return fail(level, context, lease, before, after, restoreFlags, "exception");
      }
   }

   /**
    * Restores the captured targets and decides who owns the recovery data.
    *
    * <p>A complete restore releases the lease. An incomplete restore passes the
    * snapshots to the recovery task and keeps the lease, because releasing it
    * would drop the only record of the unfinished positions.</p>
    */
   private static Outcome fail(
      ServerLevel level,
      WorldTaskContext context,
      WorldWriteCoordinator.Lease lease,
      ArrayDeque<ReversibleBlockSnapshot> before,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      int restoreFlags,
      String reason
   ) {
      if (restoreAll(level, before, restoreFlags)) {
         WorldWriteCoordinator.release(lease);
         return Outcome.RESTORED;
      }
      Outcome outcome = handOffRecovery(context, level.dimension(), before, after);
      if (outcome == Outcome.RECOVERY_BLOCKED) {
         LOGGER.error(
            "FastFormer short world transaction in {} could not hand {} unfinished positions to recovery ({}); the write lease stays locked",
            level.dimension().location(),
            before.size(),
            reason
         );
      }
      return outcome;
   }

   /**
    * Passes an unfinished capture to the recovery queue.
    *
    * <p>The recovery path reports recovery waiting only after it takes the
    * snapshots. When it refuses, this method reports a blocked recovery so that
    * no caller assumes that a safe recovery is already in progress.</p>
    *
    * @return {@link Outcome#RECOVERY_PENDING} when the recovery queue owns the
    *         snapshots, {@link Outcome#RECOVERY_BLOCKED} when they stay with the
    *         caller and the write lease stays locked
    */
   static Outcome handOffRecovery(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> before,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      return WorldHistoryManager.acceptShortTransactionRecovery(context, dimension, before, after)
         ? Outcome.RECOVERY_PENDING
         : Outcome.RECOVERY_BLOCKED;
   }

   /**
    * Reports a result that the player must see.
    *
    * <p>A restore reports the failed change. A blocked recovery reports the
    * retained safety journal. Success and a refused attempt report nothing.</p>
    */
   public static void report(Outcome outcome, ServerPlayer player) {
      if (player == null) {
         return;
      }
      if (outcome == Outcome.RESTORED) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_confirm_failed"));
      } else if (outcome == Outcome.RECOVERY_BLOCKED) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.task_recovery_blocked"));
      }
   }

   private static boolean restoreAll(
      ServerLevel level,
      ArrayDeque<ReversibleBlockSnapshot> before,
      int restoreFlags
   ) {
      boolean restored = true;
      for (ReversibleBlockSnapshot snapshot : before) {
         try {
            if (!snapshot.restore(level, restoreFlags)) {
               restored = false;
            }
         } catch (RuntimeException | Error exception) {
            restored = false;
         }
      }
      return restored;
   }
}
