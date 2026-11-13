package io.github.fastformer.server.worldwrite;

import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Owns the dimension write permissions of one server runtime, including delayed cleanup. */
public final class WorldWriteLeases implements AutoCloseable {
   private static final long RETRY_NANOS = 1_000_000_000L;
   private final Map<ResourceKey<Level>, LeaseState> leases = new HashMap<>();
   private final Map<ResourceKey<Level>, PendingCleanup> pending = new HashMap<>();
   private final LongSupplier nanoTime;
   private long generation;
   private boolean closed;

   public WorldWriteLeases() { this(System::nanoTime); }

   WorldWriteLeases(LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime); }

   /** An identity token bound to the exact runtime that granted it. */
   public static final class Lease {
      private final WorldWriteLeases scope;
      private final ResourceKey<Level> dimension;
      private final UUID owner;
      private final long generation;

      private Lease(WorldWriteLeases scope, ResourceKey<Level> dimension, UUID owner, long generation) {
         this.scope = scope;
         this.dimension = dimension;
         this.owner = owner;
         this.generation = generation;
      }

      public UUID owner() { return owner; }
      public ResourceKey<Level> dimension() { return dimension; }
      public boolean isCurrent() { return scope.isCurrent(this); }
      public boolean release() { return scope.release(this); }

      /** A delayed completion can release only this token, never a successor's permission. */
      public void releaseAfterCleanup(UnusedWriteCleanup cleanup,
         CompletionStage<? extends Optional<? extends UnusedWriteCleanup>> preparation) {
         if (cleanup != null) {
            scope.discardOrRetain(this, cleanup);
         } else if (preparation == null) {
            release();
         } else {
            preparation.whenComplete((created, failure) -> {
               if (failure != null || created == null || created.isEmpty()) release();
               else scope.discardOrRetain(this, created.orElseThrow());
            });
         }
      }

      @Override public String toString() {
         return "Lease[" + owner + "@" + dimension.location() + "#" + generation + "]";
      }
   }

   /** A new transaction cannot join even a lease held by the same player. */
   public synchronized Lease acquire(ResourceKey<Level> dimension, UUID owner) {
      if (closed || dimension == null || owner == null) return null;
      retryForOwner(owner);
      if (!available(dimension, owner) || leases.containsKey(dimension)) return null;
      return grant(dimension, owner);
   }

   /** A successor inherits the permission without opening a gap for another writer. */
   public synchronized Lease takeOver(ResourceKey<Level> dimension, UUID owner) {
      if (closed || dimension == null || owner == null) return null;
      retryForOwner(owner);
      if (!available(dimension, owner)) return null;
      LeaseState current = leases.get(dimension);
      if (current != null && !owner.equals(current.owner())) return null;
      return grant(dimension, owner);
   }

   public synchronized boolean heldBy(ResourceKey<Level> dimension, UUID owner) {
      LeaseState current = leases.get(dimension);
      return !closed && current != null && current.owner().equals(owner);
   }

   public synchronized boolean busy(ResourceKey<Level> dimension) {
      if (closed) return false;
      retry(dimension);
      return leases.containsKey(dimension) || pending.containsKey(dimension);
   }

   /** Only recovery resolving an owner's capture may use this identity-free release. */
   public synchronized boolean releaseCurrentLease(ResourceKey<Level> dimension, UUID owner) {
      LeaseState current = leases.get(dimension);
      return !closed && current != null && current.owner().equals(owner) && leases.remove(dimension, current);
   }

   private Lease grant(ResourceKey<Level> dimension, UUID owner) {
      long next = ++generation;
      leases.put(dimension, new LeaseState(owner, next));
      return new Lease(this, dimension, owner, next);
   }

   private boolean available(ResourceKey<Level> dimension, UUID owner) {
      retry(dimension);
      return !pending.containsKey(dimension) && leases.entrySet().stream()
         .noneMatch(entry -> owner.equals(entry.getValue().owner()) && !dimension.equals(entry.getKey()));
   }

   private synchronized boolean isCurrent(Lease lease) {
      LeaseState current = leases.get(lease.dimension);
      return !closed && lease.scope == this && current != null
         && current.generation() == lease.generation && current.owner().equals(lease.owner);
   }

   private synchronized boolean release(Lease lease) {
      if (!isCurrent(lease)) return false;
      PendingCleanup cleanup = pending.get(lease.dimension);
      if (cleanup != null && cleanup.lease().generation == lease.generation) return false;
      leases.remove(lease.dimension);
      return true;
   }

   private void discardOrRetain(Lease lease, UnusedWriteCleanup cleanup) {
      if (discard(cleanup)) lease.release();
      else retain(lease, cleanup);
   }

   private static boolean discard(UnusedWriteCleanup cleanup) {
      try {
         return cleanup.discardUnused();
      } catch (RuntimeException | OutOfMemoryError failure) {
         LogUtils.getLogger().error("Could not discard unused world-write preparation; keeping its lease", failure);
         return false;
      }
   }

   private synchronized void retain(Lease lease, UnusedWriteCleanup cleanup) {
      // A late callback may leave durable data for startup recovery, but cannot recreate a stopped runtime.
      if (isCurrent(lease)) pending.put(lease.dimension, new PendingCleanup(lease, cleanup, nanoTime.getAsLong() + RETRY_NANOS));
   }

   private void retry(ResourceKey<Level> dimension) {
      PendingCleanup cleanup = pending.get(dimension);
      if (cleanup == null || nanoTime.getAsLong() - cleanup.retryAfterNanos() < 0L) return;
      if (!discard(cleanup.resource())) {
         pending.put(dimension, new PendingCleanup(cleanup.lease(), cleanup.resource(), nanoTime.getAsLong() + RETRY_NANOS));
         return;
      }
      pending.remove(dimension, cleanup);
      cleanup.lease().release();
   }

   private void retryForOwner(UUID owner) {
      for (ResourceKey<Level> dimension : pending.entrySet().stream()
         .filter(entry -> owner.equals(entry.getValue().lease().owner()))
         .map(Map.Entry::getKey).toList()) retry(dimension);
   }

   @Override public synchronized void close() {
      closed = true;
      leases.clear();
      pending.clear();
   }

   private record LeaseState(UUID owner, long generation) {}
   private record PendingCleanup(Lease lease, UnusedWriteCleanup resource, long retryAfterNanos) {}
}
