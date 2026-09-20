package io.github.fastformer.fastplace.world;

import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * The records of one server instance. This class holds no server reference, so a test can
 * examine the eviction rules without a live server.
 *
 * <p>An entry is keyed by owner, dimension, and transfer id. The dimension prevents an
 * answer for one environment from settling a submission of another.</p>
 */
final class WorkspaceSubmissionBook {
   /** Bounds that one server applies. */
   static final int MAX_ENTRIES_PER_OWNER = 64;
   static final int MAX_ENTRIES_PER_SERVER = 4096;
   /** Age at which a finished entry stops answering. A running entry never ages out. */
   static final long FINISHED_TTL_MILLIS = 6L * 60L * 60L * 1000L;

   private final Map<UUID, ArrayDeque<WorkspaceSubmissionLedger.Entry>> owners = new HashMap<>();

   /**
    * Records one state.
    *
    * <p>A record may move only toward a stronger state. The order is: running work, then a
    * retryable failure, then a failure that the player cannot retry, then applied work. A
    * weaker state never replaces a stronger one, because the client would then wait again
    * or send work that the server already settled.</p>
    *
    * @return the state that this book holds after the call, or null when nothing was stored
    */
   OperationSubmissionOutcome record(
      UUID owner, ResourceLocation dimension, UUID transferId,
      OperationSubmissionOutcome outcome, long nowMillis
   ) {
      if (owner == null || dimension == null || transferId == null || outcome == null) {
         return null;
      }
      WorkspaceSubmissionLedger.Entry existing = find(owner, dimension, transferId);
      if (existing != null && rank(outcome) <= rank(existing.outcome())) {
         // The stored state is at least as strong. The caller answers with the stored
         // state, not with the state that it wanted to write.
         return existing.outcome();
      }
      ArrayDeque<WorkspaceSubmissionLedger.Entry> entries =
         this.owners.computeIfAbsent(owner, ignored -> new ArrayDeque<>());
      entries.removeIf(entry -> entry.transferId().equals(transferId) && entry.dimension().equals(dimension));
      entries.addLast(new WorkspaceSubmissionLedger.Entry(transferId, dimension, outcome, nowMillis));
      trimOwner(entries);
      trimServer();
      return outcome;
   }

   /**
    * The strength of one state.
    *
    * <p>Running work is the weakest, because it settles into something else. Applied work
    * is the strongest, because the world already holds it. A retryable failure sits below
    * a failure that the player cannot retry, because the second one removes the option to
    * send the work again.</p>
    */
   static int rank(OperationSubmissionOutcome outcome) {
      return switch (outcome) {
         case APPLIED -> 3;
         case FAILED_NONRETRYABLE, RECOVERY_REQUIRED -> 2;
         case FAILED_RETRYABLE -> 1;
         case IN_PROGRESS, IN_FLIGHT, UNKNOWN -> 0;
      };
   }

   /** True when this book already holds an entry for this transfer in this dimension. */
   boolean holds(UUID owner, ResourceLocation dimension, UUID transferId) {
      return find(owner, dimension, transferId) != null;
   }

   /** Returns the recorded entry, or null when this book holds no matching entry. */
   WorkspaceSubmissionLedger.Entry find(UUID owner, ResourceLocation dimension, UUID transferId) {
      if (owner == null || dimension == null || transferId == null) {
         return null;
      }
      ArrayDeque<WorkspaceSubmissionLedger.Entry> entries = this.owners.get(owner);
      if (entries == null) {
         return null;
      }
      for (WorkspaceSubmissionLedger.Entry entry : entries) {
         if (entry.transferId().equals(transferId) && entry.dimension().equals(dimension)) {
            return entry;
         }
      }
      return null;
   }

   /**
    * Drops finished entries that passed their time limit.
    *
    * <p>A running entry stays, because a timeout would answer {@code UNKNOWN} for work
    * that the server still performs.</p>
    */
   void expireFinished(long nowMillis) {
      long deadline = nowMillis - FINISHED_TTL_MILLIS;
      this.owners.values().removeIf(entries -> {
         entries.removeIf(entry -> !entry.outcome().open() && entry.recordedAtMillis() < deadline);
         // An owner with no entry left must leave the map. The entry limit alone does not
         // bound the map, because every owner that ever submitted keeps a queue.
         return entries.isEmpty();
      });
      trimServer();
   }

   void clearOwner(UUID owner) {
      if (owner != null) {
         this.owners.remove(owner);
      }
   }

   int sizeFor(UUID owner) {
      ArrayDeque<WorkspaceSubmissionLedger.Entry> entries = this.owners.get(owner);
      return entries == null ? 0 : entries.size();
   }

   int totalSize() {
      int total = 0;
      for (ArrayDeque<WorkspaceSubmissionLedger.Entry> entries : this.owners.values()) {
         total += entries.size();
      }
      return total;
   }

   List<WorkspaceSubmissionLedger.Entry> entriesFor(UUID owner) {
      ArrayDeque<WorkspaceSubmissionLedger.Entry> entries = this.owners.get(owner);
      return entries == null ? List.of() : List.copyOf(entries);
   }

   /**
    * Removes the oldest finished entries when one owner passes the limit.
    *
    * <p>A transfer that still runs is never removed. An eviction of a running transfer
    * would answer {@code UNKNOWN} for work that the server still performs, and the client
    * could then send the same work again.</p>
    */
   private void trimOwner(ArrayDeque<WorkspaceSubmissionLedger.Entry> entries) {
      removeOldestFinished(entries, entries.size() - MAX_ENTRIES_PER_OWNER);
      // An owner whose entries all left must not stay as an empty queue.
      this.owners.values().removeIf(ArrayDeque::isEmpty);
   }

   /**
    * Removes the oldest finished entries when the book passes its limit.
    *
    * <p>A per-owner limit alone is not a bound. The owner set grows with every player
    * that ever joins, so the book needs its own limit.</p>
    */
   private void trimServer() {
      int total = totalSize();
      if (total <= MAX_ENTRIES_PER_SERVER) {
         return;
      }
      int excess = total - MAX_ENTRIES_PER_SERVER;
      ArrayList<Map.Entry<UUID, WorkspaceSubmissionLedger.Entry>> finished = new ArrayList<>();
      for (Map.Entry<UUID, ArrayDeque<WorkspaceSubmissionLedger.Entry>> owner : this.owners.entrySet()) {
         for (WorkspaceSubmissionLedger.Entry entry : owner.getValue()) {
            if (!entry.outcome().open()) {
               finished.add(Map.entry(owner.getKey(), entry));
            }
         }
      }
      finished.sort(Comparator.comparingLong(value -> value.getValue().recordedAtMillis()));
      for (Map.Entry<UUID, WorkspaceSubmissionLedger.Entry> value : finished) {
         if (excess <= 0) {
            break;
         }
         ArrayDeque<WorkspaceSubmissionLedger.Entry> entries = this.owners.get(value.getKey());
         if (entries != null && entries.remove(value.getValue())) {
            excess--;
         }
      }
      // An owner that lost every entry must leave the map. The entry limit does not bound
      // the number of owners, so empty queues would grow without limit.
      this.owners.values().removeIf(ArrayDeque::isEmpty);
   }

   /** The number of owners that still hold an entry. */
   int ownerCount() {
      return this.owners.size();
   }

   private static void removeOldestFinished(ArrayDeque<WorkspaceSubmissionLedger.Entry> entries, int excess) {
      if (excess <= 0) {
         return;
      }
      ArrayList<WorkspaceSubmissionLedger.Entry> finished = new ArrayList<>();
      for (WorkspaceSubmissionLedger.Entry entry : entries) {
         if (!entry.outcome().open()) {
            finished.add(entry);
         }
      }
      for (WorkspaceSubmissionLedger.Entry entry : finished) {
         if (excess <= 0) {
            break;
         }
         entries.remove(entry);
         excess--;
      }
   }
}
