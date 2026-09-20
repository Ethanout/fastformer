package io.github.fastformer.fastplace.world;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * Serializes FastFormer world transactions per server and dimension.
 *
 * <p>Every transaction holds a {@link Lease} object. Only the exact lease that
 * acquired a dimension can release it, so a nested, duplicated or late release
 * cannot remove a lease that another transaction still needs.</p>
 *
 * <p>A successor keeps the current owner: a recovery task, or the next tick of
 * the same task, inherits the lease with {@link #takeOver}. That call keeps the
 * lease held and invalidates every earlier lease object of the same owner, so
 * the predecessor's late release becomes a no-op instead of opening a gap for
 * another writer.</p>
 *
 * <p>A new exclusive transaction uses {@link #acquire}. It fails while any
 * lease exists for the dimension, including a lease that the same player still
 * holds, so two different transactions of one player cannot run at the same
 * time in one dimension.</p>
 */
public final class WorldWriteCoordinator {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final long UNUSED_RETRY_NANOS = 1_000_000_000L;
   private static final Map<Object, Map<ResourceKey<Level>, LeaseState>> LEASES = new IdentityHashMap<>();
   private static final Map<Object, Map<ResourceKey<Level>, PendingUnusedJournal>> PENDING_UNUSED = new IdentityHashMap<>();
   /**
    * Monotonic lease generation. A value is never reused, so a lease object of
    * a finished transaction can never match a later lease of the same owner in
    * the same dimension.
    */
   private static long generationSequence;

   private WorldWriteCoordinator() {
   }

   /** One granted world-write permission. Release it with this exact object. */
   public static final class Lease {
      private final Object server;
      private final ResourceKey<Level> dimension;
      private final UUID owner;
      private final long generation;

      private Lease(Object server, ResourceKey<Level> dimension, UUID owner, long generation) {
         this.server = server;
         this.dimension = dimension;
         this.owner = owner;
         this.generation = generation;
      }

      public UUID owner() {
         return this.owner;
      }

      public ResourceKey<Level> dimension() {
         return this.dimension;
      }

      @Override
      public String toString() {
         return "Lease[" + this.owner + "@" + this.dimension.location() + "#" + this.generation + "]";
      }
   }

   /**
    * Starts a new exclusive transaction, or returns {@code null} when the
    * dimension is unavailable, a pending journal cleanup owns it, another owner
    * writes in it, or this owner already holds a lease.
    */
   public static synchronized Lease acquire(Object server, ResourceKey<Level> dimension, UUID owner) {
      if (server == null || dimension == null || owner == null) {
         return null;
      }
      retryUnusedJournalsForOwner(server, owner);
      if (!dimensionAvailable(server, dimension, owner)) {
         return null;
      }
      Map<ResourceKey<Level>, LeaseState> dimensions = LEASES.computeIfAbsent(server, ignored -> new HashMap<>());
      if (dimensions.containsKey(dimension)) {
         return null;
      }
      long generation = nextGeneration();
      dimensions.put(dimension, new LeaseState(owner, generation));
      return new Lease(server, dimension, owner, generation);
   }

   /**
    * Inherits the lease of the same owner for a successor transaction. The call
    * keeps the lease held, gives the successor a newer generation, and makes
    * every earlier lease object of that owner unable to release it.
    */
   public static synchronized Lease takeOver(Object server, ResourceKey<Level> dimension, UUID owner) {
      if (server == null || dimension == null || owner == null) {
         return null;
      }
      retryUnusedJournalsForOwner(server, owner);
      if (!dimensionAvailable(server, dimension, owner)) {
         return null;
      }
      Map<ResourceKey<Level>, LeaseState> dimensions = LEASES.computeIfAbsent(server, ignored -> new HashMap<>());
      LeaseState state = dimensions.get(dimension);
      if (state == null) {
         long generation = nextGeneration();
         dimensions.put(dimension, new LeaseState(owner, generation));
         return new Lease(server, dimension, owner, generation);
      }
      if (!owner.equals(state.owner)) {
         return null;
      }
      state.generation = nextGeneration();
      return new Lease(server, dimension, owner, state.generation);
   }

   /** Returns whether this exact lease is still the lease of its dimension. */
   public static synchronized boolean renew(Lease lease) {
      return currentState(lease) != null;
   }

   /**
    * Releases a lease. A stale lease, a lease from another owner and a second
    * release of one lease are ignored, so they cannot free a live transaction.
    */
   public static synchronized boolean release(Lease lease) {
      LeaseState state = currentState(lease);
      if (state == null) {
         return false;
      }
      PendingUnusedJournal pending = pendingUnused(lease.server, lease.dimension);
      if (pending != null && pending.lease().generation == lease.generation) {
         // The pending cleanup still owns the durable proof for this lease. It
         // releases the lease when the prepared file is gone.
         return false;
      }
      boolean released = releaseState(lease.server, lease.dimension, state);
      return released;
   }

   /**
    * Releases the owner's current lease without a lease object.
    *
    * <p>Use this only while resolving an owner's capture or journal, before any
    * successor transaction exists. Journal cleanup must use
    * {@link #releaseAfterUnusedJournal(Lease, PersistentRecoveryJournal,
    * CompletableFuture)} so a late callback cannot free a successor lease.</p>
    */
   public static synchronized boolean releaseCurrentLease(Object server, ResourceKey<Level> dimension, UUID owner) {
      Map<ResourceKey<Level>, LeaseState> dimensions = LEASES.get(server);
      if (dimensions == null || owner == null) {
         return false;
      }
      LeaseState state = dimensions.get(dimension);
      if (state == null || !owner.equals(state.owner)) {
         return false;
      }
      return releaseState(server, dimension, state);
   }

   public static synchronized boolean heldBy(Object server, ResourceKey<Level> dimension, UUID owner) {
      UUID current = currentOwner(server, dimension);
      return current != null && current.equals(owner);
   }

   public static synchronized boolean busy(Object server, ResourceKey<Level> dimension) {
      retryUnusedJournal(server, dimension, false);
      boolean busy = currentOwner(server, dimension) != null || pendingUnused(server, dimension) != null;
      return busy;
   }

   /**
    * Releases the lease after the cancelled journal creation can no longer
    * append, or registers the prepared file as pending cleanup.
    *
    * <p>The release uses the lease that started the cleanup. A late callback
    * therefore cannot free a successor lease of the same player, because that
    * successor has another generation.</p>
    */
   public static void releaseAfterUnusedJournal(
      Lease lease,
      PersistentRecoveryJournal journal,
      CompletableFuture<Optional<PersistentRecoveryJournal>> journalFuture
   ) {
      if (lease == null) {
         // Without the originating lease there is no identity to release. Never
         // fall back to the player key here: a later transaction of the same
         // player may own this dimension now.
         LOGGER.error("FastFormer journal cleanup without a lease identity; the lease was left untouched");
         return;
      }
      if (journal != null) {
         if (journal.discardUnused()) {
            release(lease);
         } else {
            retainUnusedJournal(lease, journal);
         }
         return;
      }
      if (journalFuture == null) {
         release(lease);
         return;
      }
      journalFuture.whenComplete((created, exception) -> {
         if (exception != null
            || created == null
            || created.isEmpty()) {
            release(lease);
         } else {
            PersistentRecoveryJournal unused = created.orElseThrow();
            if (unused.discardUnused()) {
               release(lease);
            } else {
               retainUnusedJournal(lease, unused);
            }
         }
      });
   }

   /**
    * Reports whether a new transaction may start: the owner must not already
    * write in another dimension, and no pending unused journal may own this one.
    */
   private static boolean dimensionAvailable(Object server, ResourceKey<Level> dimension, UUID owner) {
      retryUnusedJournal(server, dimension, false);
      if (pendingUnused(server, dimension) != null) {
         return false;
      }
      Map<ResourceKey<Level>, LeaseState> dimensions = LEASES.get(server);
      return dimensions == null
         || dimensions.entrySet().stream()
            .noneMatch(entry -> owner.equals(entry.getValue().owner) && !dimension.equals(entry.getKey()));
   }

   private static long nextGeneration() {
      generationSequence++;
      return generationSequence;
   }

   private static LeaseState currentState(Lease lease) {
      if (lease == null) {
         return null;
      }
      Map<ResourceKey<Level>, LeaseState> dimensions = LEASES.get(lease.server);
      if (dimensions == null) {
         return null;
      }
      LeaseState state = dimensions.get(lease.dimension);
      return state != null && state.generation == lease.generation && lease.owner.equals(state.owner) ? state : null;
   }

   private static UUID currentOwner(Object server, ResourceKey<Level> dimension) {
      Map<ResourceKey<Level>, LeaseState> dimensions = LEASES.get(server);
      LeaseState state = dimensions == null ? null : dimensions.get(dimension);
      return state == null ? null : state.owner;
   }

   private static boolean releaseState(Object server, ResourceKey<Level> dimension, LeaseState state) {
      Map<ResourceKey<Level>, LeaseState> dimensions = LEASES.get(server);
      if (dimensions == null || dimensions.get(dimension) != state) {
         return false;
      }
      dimensions.remove(dimension);
      if (dimensions.isEmpty()) {
         LEASES.remove(server);
      }
      return true;
   }

   private static synchronized void retainUnusedJournal(Lease lease, PersistentRecoveryJournal journal) {
      if (!renew(lease)) {
         // The server may already have stopped and cleared its coordinator, or
         // a successor took the lease over. Leave the prepared file for startup
         // recovery without resurrecting an in-memory lease.
         return;
      }
      PENDING_UNUSED.computeIfAbsent(lease.server, ignored -> new HashMap<>())
         .put(lease.dimension, new PendingUnusedJournal(lease, journal, System.nanoTime() + UNUSED_RETRY_NANOS));
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
               new PendingUnusedJournal(pending.lease(), pending.journal(), System.nanoTime() + UNUSED_RETRY_NANOS)
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
      // Generation-checked: a successor transaction of the same player keeps
      // its own lease.
      release(pending.lease());
   }

   private static void retryUnusedJournalsForOwner(Object server, UUID owner) {
      Map<ResourceKey<Level>, PendingUnusedJournal> dimensions = PENDING_UNUSED.get(server);
      if (dimensions == null || dimensions.isEmpty()) {
         return;
      }
      for (ResourceKey<Level> dimension : dimensions.entrySet().stream()
         .filter(entry -> owner.equals(entry.getValue().lease().owner()))
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

   public static synchronized void clear(Object server) {
      if (server != null) {
         LEASES.remove(server);
         PENDING_UNUSED.remove(server);
      }
   }

   public static synchronized void clearAll() {
      LEASES.clear();
      PENDING_UNUSED.clear();
   }

   private static final class LeaseState {
      private final UUID owner;
      private long generation;

      private LeaseState(UUID owner, long generation) {
         this.owner = owner;
         this.generation = generation;
      }
   }

   private record PendingUnusedJournal(Lease lease, PersistentRecoveryJournal journal, long retryAfterNanos) {
   }
}
