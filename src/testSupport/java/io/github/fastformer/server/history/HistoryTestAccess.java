package io.github.fastformer.server.history;

import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.memory.HistoryMemoryCache;
import io.github.fastformer.server.history.replay.HistoryReplayTask;

import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Shared typed fixture bridge. Compiled only into unit-test and GameTest outputs. */
public final class HistoryTestAccess {
   private HistoryTestAccess() {}

   public static void awaitInitialHistoryLoad(HistoryService history, ServerPlayer player, long timeoutMillis) {
      if (player == null || player.getServer() == null || timeoutMillis <= 0L) {
         throw new IllegalArgumentException("A player with a server and a positive timeout is required");
      }
      OwnerHistory owner = history.ownerIfPresent(player.getUUID());
      if (owner == null) {
         throw new IllegalStateException("Player history initialization did not start for " + player.getUUID());
      }
      CompletableFuture<Void> load = owner.loading.snapshotCompletion();
      if (owner.loading.snapshotPending()) {
         try {
            load.get(timeoutMillis, TimeUnit.MILLISECONDS);
         } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for player history initialization", exception);
         } catch (TimeoutException exception) {
            throw new IllegalStateException("Timed out after " + timeoutMillis
               + " ms waiting for player history initialization", exception);
         } catch (ExecutionException exception) {
            throw new IllegalStateException("Player history initialization failed", exception.getCause());
         }
         history.attachLoadedHistory(new WorldTaskContext(player.getServer(), player.getUUID()));
      }
      if (owner.loading.snapshotPending() || owner.loading.snapshotFailed() || owner.history == null) {
         throw new IllegalStateException("Player history initialization did not become ready");
      }
   }

   public static void installLoadedHistory(HistoryService history,
      UUID ownerId, HistoryRepository.Snapshot<? extends HistoryEntry> loaded, int limit
   ) {
      OwnerHistory owner = history.ownerState(ownerId);
      owner.historyLimit = Math.clamp(limit, 1, HistoryService.MAX_LIMIT);
      owner.loading.startSnapshot(CompletableFuture.completedFuture(new HistoryRepository.Snapshot<>(
         List.copyOf(loaded.undo()), List.copyOf(loaded.redo()), loaded.undoOrder(), loaded.redoOrder())));
      owner.loading.attachSnapshot(owner::mergeSnapshot);
   }

   public static List<UUID> historyOrder(HistoryService history, UUID ownerId, boolean undo) {
      OwnerHistory owner = history.ownerIfPresent(ownerId);
      return owner == null ? List.of() : owner.order().order(undo);
   }

   public static void addBatch(HistoryService history, UUID owner, WorldChangeBatch batch) {
      history.addBatch(new WorldTaskContext(null, owner), batch);
   }

   public static int undoSize(HistoryService history, UUID owner) {
      OwnerHistory state = history.ownerIfPresent(owner);
      HistoryMemoryCache<HistoryEntry> cache = state == null ? null : state.history;
      return cache == null ? 0 : cache.size(true);
   }

   public static void setOwnerLimit(HistoryService history, UUID owner, int limit) {
      history.ownerState(owner).historyLimit = Math.clamp(limit, 1, HistoryService.MAX_LIMIT);
   }

   public static int recoveryCaptureCount(HistoryService history, UUID owner) {
      OwnerHistory state = history.ownerIfPresent(owner);
      return state == null ? 0 : state.recovery.size();
   }

   public static WorldChangeBatch activeRecoveryBatch(HistoryService history, UUID owner) {
      OwnerHistory state = history.ownerIfPresent(owner);
      HistoryReplayTask active = state == null ? null : state.active;
      if (active == null || !active.recovery() || active.batch() == null) return null;
      if (active.batch() instanceof WorldChangeBatch block) return block;
      throw new AssertionError("Expected a block recovery batch");
   }

   public static boolean persistenceFailed(HistoryService history, UUID ownerId) {
      OwnerHistory owner = history.ownerIfPresent(ownerId);
      return owner != null && owner.persistence.dirty() && owner.persistence.idle();
   }

   public static void trackPersistence(HistoryService history,
      MinecraftServer server, UUID ownerId, boolean snapshot, CompletableFuture<Void> future
   ) {
      history.ownerState(ownerId).trackPersistence(new WorldTaskContext(server, ownerId), snapshot, future);
   }

   public static boolean durableIndexFailed(HistoryService history, UUID ownerId) {
      OwnerHistory owner = history.ownerIfPresent(ownerId);
      OwnerHistory.PreparedCommit commit = owner == null ? null : owner.durableCommit;
      return commit != null && commit.publication().indexFailed();
   }

   public static int ownerCount(HistoryService history) {
      return history.ownerCount();
   }

   public static void setPersistenceRetention(HistoryService history,
      UUID ownerId, int pendingPersistence, boolean persistenceDirty, boolean durableCommit
   ) {
      OwnerHistory owner = history.ownerState(ownerId);
      if (persistenceDirty) owner.persistence.track(false,
         CompletableFuture.failedFuture(new IllegalStateException("Test persistence failure")), Runnable::run, ignored -> {});
      for (int index = 0; index < pendingPersistence; index++) {
         owner.persistence.track(false, new CompletableFuture<>(), Runnable::run, ignored -> {});
      }
      owner.durableCommit = durableCommit
         ? new OwnerHistory.PreparedCommit(null, null, null)
         : null;
   }

   public static boolean ownerPresent(HistoryService history, UUID owner) {
      return history.ownerIfPresent(owner) != null;
   }

   public static List<ReversibleBlockSnapshot> sourceSnapshots(WorldChangeBatch batch, boolean undo) {
      return batch.sourceSnapshots(undo);
   }

   public static List<ReversibleBlockSnapshot> targetSnapshots(WorldChangeBatch batch, boolean undo) {
      return batch.targetSnapshots(undo);
   }
}
