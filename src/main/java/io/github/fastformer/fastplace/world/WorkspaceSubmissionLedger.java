package io.github.fastformer.fastplace.world;

import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * Bounded record of workspace submission outcomes, owned by one server instance.
 *
 * <p>The client cannot learn the result of a submission while it is away. The server
 * completes the world write offline, and the result packet has no receiver. This ledger
 * keeps the answer until the client asks for it after a reconnect.</p>
 *
 * <p>An entry is keyed by owner, dimension, and transfer id. A dimension in the key
 * prevents an answer for one environment from settling a submission of another. A
 * transfer enters the ledger as {@link OperationSubmissionOutcome#IN_PROGRESS} when the
 * task is accepted, and leaves it as a final state.</p>
 *
 * <p>The ledger of a server instance is dropped when that server stops. A different
 * save in the same address therefore starts with an empty ledger, and every old
 * transfer answers {@code UNKNOWN} instead of a result that belongs to another save.</p>
 */
public final class WorkspaceSubmissionLedger {
   /** Finished transfers kept for one owner. The oldest finished entry leaves first. */
   public static final int MAX_ENTRIES_PER_OWNER = WorkspaceSubmissionBook.MAX_ENTRIES_PER_OWNER;
   /** Finished transfers kept for one server, over all owners. */
   public static final int MAX_ENTRIES_PER_SERVER = WorkspaceSubmissionBook.MAX_ENTRIES_PER_SERVER;
   /** Age at which a finished entry stops answering. A running entry never ages out. */
   public static final long FINISHED_TTL_MILLIS = WorkspaceSubmissionBook.FINISHED_TTL_MILLIS;
   private static final Map<MinecraftServer, WorkspaceSubmissionBook> SERVERS = new IdentityHashMap<>();

   private WorkspaceSubmissionLedger() {
   }

   /** One recorded outcome. */
   public record Entry(
      UUID transferId, ResourceLocation dimension, OperationSubmissionOutcome outcome, long recordedAtMillis
   ) {
      public Entry {
         if (transferId == null || dimension == null || outcome == null) {
            throw new IllegalArgumentException("A ledger entry needs a transfer id, a dimension, and an outcome");
         }
      }
   }

   /** Records that a task was accepted and now runs. */
   public static void begin(
      MinecraftServer server, UUID owner, ResourceLocation dimension, UUID transferId
   ) {
      record(server, owner, dimension, transferId, OperationSubmissionOutcome.IN_PROGRESS);
   }

   /**
    * Records the final state of a finished transfer.
    *
    * <p>The returned state is the state that the ledger holds after the call. A caller
    * must deliver that state, not the state that it intended to record, because the ledger
    * may already hold something stronger.</p>
    *
    * @param recoveryCreated true when the server moved the work to its recovery path
    * @return the state that this ledger holds for the transfer
    */
   public static OperationSubmissionOutcome finish(
      MinecraftServer server, UUID owner, ResourceLocation dimension, UUID transferId,
      boolean accepted, boolean retryable, boolean recoveryCreated
   ) {
      OperationSubmissionOutcome intended = stateFor(accepted, retryable, recoveryCreated);
      OperationSubmissionOutcome stored = record(server, owner, dimension, transferId, intended);
      return stored == null ? intended : stored;
   }

   /**
    * Maps the three report flags of one finished transfer to one state.
    *
    * <p>Pure, so a test needs no server. A recovery handoff outranks a retryable failure,
    * because the journal owns the outcome and a retry must not repeat the same work.</p>
    */
   public static OperationSubmissionOutcome stateFor(
      boolean accepted, boolean retryable, boolean recoveryCreated
   ) {
      if (accepted) {
         return OperationSubmissionOutcome.APPLIED;
      }
      if (recoveryCreated) {
         return OperationSubmissionOutcome.RECOVERY_REQUIRED;
      }
      return retryable
         ? OperationSubmissionOutcome.FAILED_RETRYABLE
         : OperationSubmissionOutcome.FAILED_NONRETRYABLE;
   }

   /**
    * Answers the state of one transfer.
    *
    * <p>A transfer that still runs keeps its {@code IN_PROGRESS} answer, because only a
    * finished entry can age out. An aged-out or unknown transfer answers {@code UNKNOWN}.</p>
    *
    * @return the recorded state, or {@code UNKNOWN} when this ledger holds no entry
    */
   public static OperationSubmissionOutcome outcomeFor(
      MinecraftServer server, UUID owner, ResourceLocation dimension, UUID transferId
   ) {
      Entry entry = find(server, owner, dimension, transferId);
      return entry == null ? OperationSubmissionOutcome.UNKNOWN : entry.outcome();
   }

   /** Returns the recorded entry, or null when this ledger holds no matching entry. */
   public static Entry find(
      MinecraftServer server, UUID owner, ResourceLocation dimension, UUID transferId
   ) {
      if (server == null || owner == null || dimension == null || transferId == null) {
         return null;
      }
      WorkspaceSubmissionBook book = SERVERS.get(server);
      return book == null ? null : book.find(owner, dimension, transferId);
   }

   /**
    * Drops finished entries that passed their time limit.
    *
    * <p>The world task scheduler calls this each tick. A running entry stays, because a
    * timeout would answer {@code UNKNOWN} for work that the server still performs.</p>
    */
   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      WorkspaceSubmissionBook book = SERVERS.get(server);
      if (book != null) {
         book.expireFinished(System.currentTimeMillis());
      }
   }

   /** Records one state. Returns the state the book holds after the call, or null. */
   private static OperationSubmissionOutcome record(
      MinecraftServer server, UUID owner, ResourceLocation dimension, UUID transferId,
      OperationSubmissionOutcome outcome
   ) {
      if (server == null) {
         return null;
      }
      return SERVERS
         .computeIfAbsent(server, ignored -> new WorkspaceSubmissionBook())
         .record(owner, dimension, transferId, outcome, System.currentTimeMillis());
   }

   /** Drops the ledger of one server instance. */
   public static void clearServer(MinecraftServer server) {
      if (server != null) {
         SERVERS.remove(server);
      }
   }

   /** Drops the entries of one owner on one server. */
   public static void clearOwner(MinecraftServer server, UUID owner) {
      WorkspaceSubmissionBook book = server == null ? null : SERVERS.get(server);
      if (book != null) {
         book.clearOwner(owner);
      }
   }

   /** Drops every ledger. Used by global teardown and by tests. */
   public static void clearAll() {
      SERVERS.clear();
   }
}
