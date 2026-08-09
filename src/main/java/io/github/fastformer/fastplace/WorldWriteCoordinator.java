package io.github.fastformer.fastplace;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Serializes FastFormer world transactions per server and dimension.
 *
 * <p>The lease is owned by the player UUID rather than a particular task so a
 * partially-written placement can transfer directly to that player's recovery
 * task without opening a gap for another writer.</p>
 */
final class WorldWriteCoordinator {
   private static final long UNUSED_RETRY_NANOS = 1_000_000_000L;
   private static final Map<Object, Map<ResourceKey<Level>, UUID>> OWNERS = new IdentityHashMap<>();
   private static final Map<Object, Map<ResourceKey<Level>, PendingUnusedJournal>> PENDING_UNUSED = new IdentityHashMap<>();

   private WorldWriteCoordinator() {
   }

   static synchronized boolean tryAcquire(Object server, ResourceKey<Level> dimension, UUID owner) {
      if (server == null || dimension == null || owner == null) {
         return false;
      }
      retryUnusedJournalsForOwner(server, owner);
      retryUnusedJournal(server, dimension, false);
      if (pendingUnused(server, dimension) != null) {
         return false;
      }
      Map<ResourceKey<Level>, UUID> dimensions = OWNERS.computeIfAbsent(server, ignored -> new HashMap<>());
      if (dimensions.entrySet().stream().anyMatch(entry -> owner.equals(entry.getValue()) && !dimension.equals(entry.getKey()))) {
         return false;
      }
      UUID current = dimensions.get(dimension);
      if (current != null && !current.equals(owner)) {
         return false;
      }
      dimensions.put(dimension, owner);
      return true;
   }

   static synchronized boolean heldBy(Object server, ResourceKey<Level> dimension, UUID owner) {
      Map<ResourceKey<Level>, UUID> dimensions = OWNERS.get(server);
      return dimensions != null && owner != null && owner.equals(dimensions.get(dimension));
   }

   static synchronized boolean busy(Object server, ResourceKey<Level> dimension) {
      retryUnusedJournal(server, dimension, false);
      Map<ResourceKey<Level>, UUID> dimensions = OWNERS.get(server);
      return dimensions != null && dimensions.containsKey(dimension);
   }

   static synchronized void release(Object server, ResourceKey<Level> dimension, UUID owner) {
      PendingUnusedJournal pending = pendingUnused(server, dimension);
      if (pending != null && pending.owner().equals(owner)) {
         return;
      }
      releaseLease(server, dimension, owner);
   }

   private static void releaseLease(Object server, ResourceKey<Level> dimension, UUID owner) {
      Map<ResourceKey<Level>, UUID> dimensions = OWNERS.get(server);
      if (dimensions == null || owner == null || !owner.equals(dimensions.get(dimension))) {
         return;
      }
      dimensions.remove(dimension);
      if (dimensions.isEmpty()) {
         OWNERS.remove(server);
      }
   }

   static void releaseAfterUnusedJournal(
      Object server,
      ResourceKey<Level> dimension,
      UUID owner,
      PersistentRecoveryJournal journal,
      CompletableFuture<Optional<PersistentRecoveryJournal>> journalFuture
   ) {
      if (journal != null) {
         if (journal.discardUnused()) {
            release(server, dimension, owner);
         } else {
            retainUnusedJournal(server, dimension, owner, journal);
         }
         return;
      }
      if (journalFuture == null) {
         release(server, dimension, owner);
         return;
      }
      journalFuture.whenComplete((created, exception) -> {
         if (exception != null
            || created == null
            || created.isEmpty()) {
            release(server, dimension, owner);
         } else {
            PersistentRecoveryJournal unused = created.orElseThrow();
            if (unused.discardUnused()) {
               release(server, dimension, owner);
            } else {
               retainUnusedJournal(server, dimension, owner, unused);
            }
         }
      });
   }

   private static synchronized void retainUnusedJournal(
      Object server,
      ResourceKey<Level> dimension,
      UUID owner,
      PersistentRecoveryJournal journal
   ) {
      Map<ResourceKey<Level>, UUID> leases = OWNERS.get(server);
      if (leases == null || !owner.equals(leases.get(dimension))) {
         // The server may already have stopped and cleared its coordinator.
         // Leave the prepared file for startup recovery without resurrecting
         // an in-memory lease for the dead server instance.
         return;
      }
      PENDING_UNUSED.computeIfAbsent(server, ignored -> new HashMap<>())
         .put(dimension, new PendingUnusedJournal(owner, journal, System.nanoTime() + UNUSED_RETRY_NANOS));
   }

   private static void retryUnusedJournal(Object server, ResourceKey<Level> dimension, boolean force) {
      PendingUnusedJournal pending = pendingUnused(server, dimension);
      if (pending == null || !force && System.nanoTime() < pending.retryAfterNanos()) {
         return;
      }
      Map<ResourceKey<Level>, PendingUnusedJournal> dimensions = PENDING_UNUSED.get(server);
      if (!pending.journal().discardUnused()) {
         if (dimensions != null) {
            dimensions.put(
               dimension,
               new PendingUnusedJournal(pending.owner(), pending.journal(), System.nanoTime() + UNUSED_RETRY_NANOS)
            );
         }
         return;
      }
      if (dimensions != null) {
         dimensions.remove(dimension, pending);
         if (dimensions.isEmpty()) {
            PENDING_UNUSED.remove(server);
         }
      }
      releaseLease(server, dimension, pending.owner());
   }

   private static void retryUnusedJournalsForOwner(Object server, UUID owner) {
      Map<ResourceKey<Level>, PendingUnusedJournal> dimensions = PENDING_UNUSED.get(server);
      if (dimensions == null || dimensions.isEmpty()) {
         return;
      }
      for (ResourceKey<Level> dimension : dimensions.entrySet().stream()
         .filter(entry -> owner.equals(entry.getValue().owner()))
         .map(Map.Entry::getKey)
         .toList()) {
         retryUnusedJournal(server, dimension, false);
      }
   }

   static synchronized void retryUnusedJournalForTest(Object server, ResourceKey<Level> dimension) {
      retryUnusedJournal(server, dimension, true);
   }

   private static PendingUnusedJournal pendingUnused(Object server, ResourceKey<Level> dimension) {
      Map<ResourceKey<Level>, PendingUnusedJournal> dimensions = PENDING_UNUSED.get(server);
      return dimensions == null ? null : dimensions.get(dimension);
   }

   static synchronized void clear(Object server) {
      if (server != null) {
         OWNERS.remove(server);
         PENDING_UNUSED.remove(server);
      }
   }

   static synchronized void clearAll() {
      OWNERS.clear();
      PENDING_UNUSED.clear();
   }

   private record PendingUnusedJournal(UUID owner, PersistentRecoveryJournal journal, long retryAfterNanos) {
   }
}
