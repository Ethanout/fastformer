package io.github.fastformer.client.session;

import com.mojang.logging.LogUtils;
import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.session.draft.ClientDraftLoadState;
import io.github.fastformer.client.session.draft.ClientDraftRepository.ReceiptVerdict;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.ClientOperationDraftCodec;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.receipt.DeferredReceiptSaves;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceipt;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceiptCodec;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceiptStore;
import io.github.fastformer.client.session.receipt.ReceiptReadState;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.scope.ClientScopeId;
import io.github.fastformer.client.session.storage.ClientStorageQueue;
import io.github.fastformer.client.session.submission.OperationDraftSettlement;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Owns top-level client session transitions and keeps them out of event handlers. */
public final class ClientSessionSystem {
   private static final ClientSessionSystem INSTANCE = new ClientSessionSystem();
   private static final Logger LOGGER = LogUtils.getLogger();

   private final Map<ClientScopeId, ClientScope> playerSessions = new HashMap<>();
   private final io.github.fastformer.client.operation.selection.ClientSelectionSession unboundSelection =
      new io.github.fastformer.client.operation.selection.ClientSelectionSession();
   private final io.github.fastformer.client.input.ClientInputSession unboundInput =
      new io.github.fastformer.client.input.ClientInputSession(unboundSelection.gestures());
   private boolean reclaimScopesPending;
   /** Scopes whose applied-draft cleanup already ran in this process. */
   private final java.util.Set<ClientScopeId> cleanupReplayed = new java.util.HashSet<>();
   private ClientScope current;
   private Object callbackConnection;
   private UUID callbackSessionId;
   private final ClientStorageQueue storage = new ClientStorageQueue();
   private final DeferredReceiptSaves unsentReceiptSaves = new DeferredReceiptSaves(storage, System::nanoTime, (path, failure) -> {
      LOGGER.warn("Unable to save the unsent submission receipt at {}; the save will retry", path, failure);
      showDraftMessage("fastformer.message.operation_receipt_save_failed");
   });

   public void drainStorageResults() {
      storage.drain();
      unsentReceiptSaves.tick();
      if (reclaimScopesPending && storage.idle() && unsentReceiptSaves.pendingCount() == 0) {
         reclaimInactiveScopes();
      }
   }

   ClientSessionSystem() {
   }

   public static ClientSessionSystem instance() {
      return INSTANCE;
   }

   /** Returns the most recently active player session, including across disconnects. */
   public ClientScope currentScope() {
      return current;
   }

   private ClientScopeId currentScopeId() {
      return current == null ? null : current.id();
   }

   /** Input and selection share one gesture owner even before any world has been attached. */
   public io.github.fastformer.client.input.ClientInputSession inputSession() {
      return current == null ? unboundInput : current.inputSession();
   }

   public io.github.fastformer.client.operation.selection.ClientSelectionSession selectionSession() {
      return current == null ? unboundSelection : current.selectionSession();
   }

   public void clearUnboundInteraction() {
      unboundInput.reset();
      unboundSelection.clearLiveInteraction();
   }

   public ClientScope forCurrent(Minecraft minecraft) {
      observePlayer(minecraft);
      return current;
   }

   /** Returns the session isolated to one connection, player, and dimension. */
   public ClientScope forScope(ClientScopeId key) {
      return playerSessions.computeIfAbsent(key, ClientScope::new);
   }

   ClientScope activateScope(UUID playerId, String connection, String dimension) {
      ClientScopeId nextKey = new ClientScopeId(connection, dimension, playerId);
      ClientScopeId currentKey = currentScopeId();
      boolean firstActivation = currentKey == null;
      boolean scopeChanged = currentKey != null && !currentKey.equals(nextKey);
      if (scopeChanged
         && currentKey.connection().equals(nextKey.connection())
         && currentKey.playerId().equals(nextKey.playerId()) && current != null) {
         // A dimension change inside one connection unloads the client world first, which
         // suspends the live draft into the file of the environment being left. Keep that
         // draft and its file: deleting it here destroyed the only copy before the new
         // environment had returned any snapshot that could confirm or reject it.
         current.detachEnvironment();
      }
      else if (scopeChanged && current != null) {
         current.resetInput();
      }
      if (scopeChanged) forScope(nextKey).drafts().retryTransientFailure();
      if (scopeChanged && forScope(nextKey).receipts().retryTransientFailure()) {
         this.rearmDraftReadAfterReceipt(nextKey);
      }
      if (scopeChanged || firstActivation) {
         // Every entry into an environment is a cleanup boundary. The applied drafts of
         // this scope are cleared once more before the draft file is read.
         this.cleanupReplayed.remove(nextKey);
      }
      ClientScope next = playerSessions.computeIfAbsent(nextKey, ClientScope::new);
      if (scopeChanged || firstActivation) next.resetInput();
      current = next;
      if (scopeChanged) {
         reclaimScopesPending = true;
         if (storage.idle() && unsentReceiptSaves.pendingCount() == 0) reclaimInactiveScopes();
      }
      return next;
   }

   /** Keeps unsaved or unreadable scopes; successful disk state can be loaded on the next entry. */
   void reclaimInactiveScopes() {
      if (!storage.idle() || unsentReceiptSaves.pendingCount() != 0) return;
      reclaimScopesPending = false;
      var entries = playerSessions.entrySet().iterator();
      while (entries.hasNext()) {
         var entry = entries.next();
         ClientScopeId key = entry.getKey();
         ClientScope session = entry.getValue();
         if (session == current || liveWorkBlocksDraftRead(session)) continue;
         if (!session.drafts().canEvict(session.suspendedOperationDraftData())) continue;
         if (!session.receipts().canEvict()) continue;
         entries.remove();
         cleanupReplayed.remove(key);
      }
   }

   /** Records the current player identity without coupling the session box to LocalPlayer lifetime. */
   public void observePlayer(Minecraft minecraft) {
      storage.drain();
      if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
         return;
      }
      String connection = connectionIdentity(minecraft);
      String dimension = minecraft.level == null ? "" : minecraft.level.dimension().location().toString();
      ClientScope session = activateScope(minecraft.player.getUUID(), connection, dimension);
      // The cleanup runs first. An applied draft must never return to the editor, because
      // the world already holds its result. The read below would stage that draft.
      if (currentScopeId() != null && this.cleanupReplayed.add(currentScopeId())) {
         this.replayPendingCleanups();
      }
      this.loadDraftOnce(minecraft, session);
   }

   /**
    * Saves the current draft without extending transient input state.
    *
    * @param identity             server selection identity, or null when none is live
    * @param originWhenNoIdentity origin to record when no identity confirms the draft
    */
   public void suspendCurrentDraft(
      OperationDraftIdentity identity, OperationSubmissionOrigin originWhenNoIdentity
   ) {
      suspendCurrentDraft(identity, originWhenNoIdentity, null);
   }

   /**
    * Saves the current draft and ties it to a submission when one owns the workspace.
    *
    * @param submissionId the submission that owns the live workspace, or null when the
    *                     workspace is the player's own work
    */
   public void suspendCurrentDraft(
      OperationDraftIdentity identity, OperationSubmissionOrigin originWhenNoIdentity, UUID submissionId
   ) {
      if (this.current == null || currentScopeId() == null) return;
      this.current.suspendOperationDraft(identity, originWhenNoIdentity, submissionId);
      ClientOperationDraft draft = this.current.suspendedOperationDraftData();
      if (draft == null) return;
      var owner = current;
      Path file = owner.drafts().file();
      boolean queued = storage.submitPrepared(() -> {
         Object ticket = OperationClipboardStore.reserve(file);
         return new ClientStorageQueue.Task<>(() -> {
            OperationClipboardStore.save(file, ClientOperationDraftCodec.encode(draft), ticket);
            return true;
         }, (saved, error) -> {
            if (!OperationClipboardStore.current(file, ticket)) return;
            if (error == null) {
               owner.drafts().confirmDurable(draft);
               reclaimScopesPending = true;
            }
            else { LOGGER.warn("Unable to save the FastFormer client operation draft", error); showDraftMessage("fastformer.message.operation_draft_save_failed"); }
         });
      });
      if (!queued) showDraftMessage("fastformer.message.operation_draft_save_failed");
   }

   /**
    * Applies a suspended draft against the live server selection.
    *
    * <p>The durable copy leaves only when the draft returned to the editor. A rejected
    * restore keeps the file, because that file is then the only remaining copy. The
    * staged copy is consumed either way, so the restore prompt cannot return in a loop.</p>
    */
   public boolean restoreCurrentDraft(OperationDraftIdentity identity) {
      if (this.current == null) return false;
      boolean hadStagedDraft = this.current.suspendedOperationDraft();
      boolean restored = this.current.restoreSuspendedOperationDraft(identity);
      if (!hadStagedDraft) return false;
      if (restored) {
         // The draft returned to the editor, so its durable copy is spent.
         this.deleteCurrentDraftFile();
         if (currentScopeId() != null) {
            current.drafts().markReady();
         }
         return true;
      }
      // The server selection changed, so the draft cannot return now. Keep the file and
      // record a retryable state: entering this environment again reads the file once
      // more. Deleting it here destroyed the only remaining copy.
      if (currentScopeId() != null) {
         current.drafts().restoreRejected();
      }
      showDraftMessage("fastformer.message.operation_draft_restore_kept");
      return false;
   }

   /**
    * Writes the live workspace as the durable draft of a submission that is about to leave.
    *
    * <p>This runs before the send, together with the receipt. Both files must exist before
    * the submission leaves, because the process can stop at any moment and the server
    * result then has no receiver. A failed write keeps the previous file: the store
    * replaces the file with an atomic move, so an incomplete write never replaces it.</p>
    *
    * @return true when the save entered the bounded storage queue
    */
   public boolean persistSubmittedDraft(
      OperationDraftIdentity identity, OperationSubmissionOrigin originWhenNoIdentity, UUID transferId,
      io.github.fastformer.workspace.submission.OperationWorkspacePlan plan, java.util.function.BooleanSupplier owned,
      java.util.function.Consumer<io.github.fastformer.client.placement.ClientPlacementRouter.WorkspaceSubmission> complete
   ) {
      if (this.current == null || currentScopeId() == null || transferId == null) return false;
      ClientOperationDraft draft =
         this.current.buildSubmittedDraft(identity, originWhenNoIdentity, transferId);
      if (draft == null) return false;
      ClientScopeId key = currentScopeId();
      var session = current;
      var store = receiptStore(key);
      var receipt = new OperationSubmissionReceipt(transferId, originWhenNoIdentity, identity,
         ResourceLocation.parse(key.dimension()), OperationSubmissionOutcome.IN_FLIGHT, System.currentTimeMillis());
      if (store.readOnly() || !store.canAccept(transferId)) return false;
      if (store.find(transferId).map(existing -> existing.outcome().applied()).orElse(false)) return false;
      Path receiptFile = receiptFileFor(key), draftFile = draftFileFor(key);
      return storage.submitPrepared(() -> {
         if (!store.record(receipt)) throw new IllegalStateException("The submission receipt cannot be recorded");
         var receipts = store.all();
         Object receiptTicket = OperationClipboardStore.reserve(receiptFile), draftTicket = OperationClipboardStore.reserve(draftFile);
         return new ClientStorageQueue.Task<>(() -> {
            var submission = io.github.fastformer.client.placement.ClientPlacementRouter.prepareWorkspace(plan, transferId);
            OperationClipboardStore.save(receiptFile, OperationSubmissionReceiptCodec.encode(receipts), receiptTicket);
            OperationClipboardStore.save(draftFile, ClientOperationDraftCodec.encode(draft), draftTicket);
            return submission;
         }, (submission, error) -> {
            boolean durable = error == null && OperationClipboardStore.current(receiptFile, receiptTicket)
               && OperationClipboardStore.current(draftFile, draftTicket);
            if (durable) { store.confirmSaved(receipts); session.drafts().markReady(); }
            if (current != session || !java.util.Objects.equals(currentScopeId(), key) || !owned.getAsBoolean()) {
               recordUnsentSubmission(store, receipt, receiptFile);
               return;
            }
            if (!durable) {
               recordUnsentSubmission(store, receipt, receiptFile);
               LOGGER.warn("Unable to persist the workspace submission", error);
               showDraftMessage("fastformer.message.operation_draft_save_failed");
            }
            complete.accept(durable ? submission : null);
         });
      });
   }

   private void recordUnsentSubmission(OperationSubmissionReceiptStore store, OperationSubmissionReceipt receipt, Path file) {
      store.record(receipt.withOutcome(OperationSubmissionOutcome.FAILED_RETRYABLE, System.currentTimeMillis()));
      unsentReceiptSaves.request(file, store);
   }

   /**
    * Clears the staged draft of the current scope and its durable file.
    *
    * <p>Only an explicit player discard uses this path. A submission result uses
    * {@link #settleAppliedSubmission(UUID)}, which clears each owner on its own.</p>
    */
   public void discardCurrentDraft() {
      if (this.current != null) this.current.discardSuspendedOperationDraft();
      this.deleteCurrentDraftFile();
      if (currentScopeId() != null) {
         current.drafts().markReady();
      }
   }

   /** The submission id of the staged draft, or null when nothing is staged. */
   public UUID stagedSubmissionId() {
      if (this.current == null) return null;
      ClientOperationDraft staged = this.current.suspendedOperationDraftData();
      return staged == null ? null : staged.submissionId();
   }

   /**
    * Reads the ownership of the durable draft file without removing it.
    *
    * <p>Three answers are possible, because two are not enough. A missing file is a
    * confirmed absence. A file that names its submission is a confirmed owner. A file
    * that cannot be read is unknown, and the client must not delete it.</p>
    */
   public OperationDraftSettlement.DurableProbe probeDurableDraft() {
      if (currentScopeId() == null) return OperationDraftSettlement.DurableProbe.absent();
      try {
         return current.drafts().probe();
      } catch (IOException | RuntimeException exception) {
         LOGGER.warn("Unable to read the FastFormer draft ownership; the file stays", exception);
         return OperationDraftSettlement.DurableProbe.unreadable();
      }
   }

   /**
    * Clears the staged copy and the durable copy of one submission.
    *
    * <p>Each owner is examined on its own. A match on one owner never removes the copy of
    * the other, so a draft that the player built after this submission always stays.</p>
    */
   public OperationDraftSettlement.Result clearDraftCopies(UUID transferId) {
      OperationDraftSettlement.Ownership staged = OperationDraftSettlement.resolveStaged(
         transferId,
         this.stagedSubmissionId(),
         () -> {
            if (this.current != null) {
               var draft = this.current.suspendedOperationDraftData();
               this.current.discardSuspendedOperationDraft();
               if (draft != null && draft.remainder() != null) {
                  this.current.stageSuspendedOperationDraft(draft.remainder().asDraft());
               }
            }
         }
      );
      OperationDraftSettlement.Ownership durable = OperationDraftSettlement.resolveDurable(
         transferId,
         this.probeDurableDraft(),
         () -> currentScopeId() != null && settleDraftFile(currentScopeId(), transferId)
      );
      return OperationDraftSettlement.combine(staged, durable);
   }

   private boolean settleDraftFile(ClientScopeId key, UUID transferId) {
      try {
         forScope(key).drafts().settle(transferId);
         reclaimScopesPending = true;
         return true;
      } catch (IOException | RuntimeException failure) {
         LOGGER.warn("Unable to preserve the unselected operation draft", failure);
         return false;
      }
   }

   /**
    * Settles one applied submission.
    *
    * <p>The order is: record the applied result with a pending cleanup, clear the two
    * copies, then record the cleanup. A stop at any point leaves a durable record that
    * still names the pending cleanup, so the next boundary finishes the work. The client
    * never depends on a later server answer, because the server ledger can restart and
    * then answer {@code UNKNOWN}.</p>
    *
    * @return the settlement result. {@link OperationDraftSettlement.Applied#confirmed()}
    *         is false when a copy could not be cleared or the record could not be saved.
    */
   public OperationDraftSettlement.Applied settleAppliedSubmission(UUID transferId) {
      if (currentScopeId() == null || transferId == null) {
         return new OperationDraftSettlement.Applied(
            OperationDraftSettlement.combine(
               OperationDraftSettlement.Ownership.UNCONFIRMED,
               OperationDraftSettlement.Ownership.UNCONFIRMED
            ),
            false
         );
      }
      OperationSubmissionReceiptStore store = receiptStore(currentScopeId());
      OperationSubmissionReceipt existing = store.find(transferId).orElse(null);
      if (existing != null && existing.outcome() != OperationSubmissionOutcome.APPLIED) {
         // The applied result is durable before the copies leave. Its cleanup flag is
         // true, so a stop between the two steps still owes the cleanup.
         store.record(existing.withOutcome(OperationSubmissionOutcome.APPLIED, System.currentTimeMillis()));
      }
      this.persistIfDirty(store);
      OperationDraftSettlement.Result drafts = this.clearDraftCopies(transferId);
      if (drafts.confirmed()) {
         store.confirmCleanup(transferId, System.currentTimeMillis());
      }
      this.persistIfDirty(store);
      if (store.readOnly()) {
         // The receipt file could not be read or belongs to another version, so the client
         // cannot record anything in it. The copies still had to leave, and they did, but
         // the client must not report a durable state that it cannot prove.
         return new OperationDraftSettlement.Applied(drafts, false);
      }
      // The store compares memory with the disk. A false result means the durable record
      // does not hold what the client knows, so the caller must not claim a settled state.
      // A later call retries the write, because the store stays dirty until a save works.
      return new OperationDraftSettlement.Applied(drafts, !store.dirty());
   }

   /**
    * Writes the receipt store when its memory differs from the disk.
    *
    * <p>The dirty flag is the only honest answer to "did this reach the disk". A caller
    * that rechecks it after a failed write sees the failure, and a later call retries the
    * same write instead of assuming the memory state is durable.</p>
    *
    * @return true when memory and disk agree
    */
   private boolean persistIfDirty(OperationSubmissionReceiptStore store) {
      if (currentScopeId() == null || !store.dirty()) {
         return !store.dirty();
      }
      saveReceiptStore(currentScopeId(), "fastformer.message.operation_receipt_save_failed");
      return !store.dirty();
   }

   /**
    * Replays the draft cleanup of every applied submission of the current scope.
    *
    * <p>This runs at a scope boundary, before the draft file is read. The known applied
    * result is already on disk, so the replay needs no server answer. It returns the
    * number of submissions whose cleanup this pass confirmed.</p>
    */
   public int replayPendingCleanups() {
      if (currentScopeId() == null) return 0;
      OperationSubmissionReceiptStore store = receiptStore(currentScopeId());
      int cleared = 0;
      for (OperationSubmissionReceipt receipt : store.needingCleanup()) {
         OperationDraftSettlement.Result drafts = this.clearDraftCopies(receipt.transferId());
         if (drafts.confirmed()) {
            store.confirmCleanup(receipt.transferId(), System.currentTimeMillis());
            cleared++;
         }
      }
      // The write is attempted whenever the store differs from the disk, including a
      // failure left by an earlier boundary. A failed write keeps the store dirty, so the
      // next boundary repeats it instead of assuming the cleanup is recorded.
      this.persistIfDirty(store);
      return cleared;
   }

   /**
    * Reads what the receipt file says about one submission.
    *
    * <p>A file that cannot be read is not the same as a file with no entry. The file may
    * hold an applied result for this very submission, so the answer is unknown and the
    * client must not act as if the submission were new.</p>
    *
    */
   private ReceiptVerdict receiptVerdict(ClientScopeId key, UUID transferId) {
      if (transferId == null) {
         // A draft that names no submission has no receipt to answer for it.
         return ReceiptVerdict.NOT_APPLIED;
      }
      if (key == null) return ReceiptVerdict.UNKNOWN;
      OperationSubmissionReceiptStore store = receiptStore(key);
      if (store.readOnly()) {
         return ReceiptVerdict.UNKNOWN;
      }
      OperationSubmissionReceipt receipt = store.find(transferId).orElse(null);
      if (receipt == null) {
         return ReceiptVerdict.NOT_APPLIED;
      }
      return receipt.outcome() == OperationSubmissionOutcome.APPLIED
         ? ReceiptVerdict.APPLIED
         : ReceiptVerdict.NOT_APPLIED;
   }

   /**
    * Clears a failed read of the current scope so the next scope tick reads the file again.
    *
    * <p>This is the explicit retry boundary. A failed read never repeats on its own and
    * never deletes the file.
    */
   public boolean retryCurrentDraftLoad() {
      if (currentScopeId() == null) return false;
      return current.drafts().retry();
   }

   public ClientDraftLoadState currentDraftLoadState() {
      return currentScopeId() == null ? ClientDraftLoadState.NOT_LOADED : this.draftLoadState(currentScopeId());
   }

   ClientDraftLoadState draftLoadState(ClientScopeId key) {
      return key == null
         ? ClientDraftLoadState.NOT_LOADED
         : forScope(key).drafts().readState();
   }

   public boolean currentDraftPending() {
      return this.current != null && this.current.suspendedOperationDraft();
   }

   /** Accepts a server snapshot and binds its callback session to the current connection. */
   public boolean acceptsPreviewCallback(OperationCallbackScope callbackScope, Connection sourceConnection) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
         return false;
      }
      Connection currentConnection = minecraft.getConnection().getConnection();
      if (!matchesOperationCallbackScope(
         callbackScope,
         minecraft.player.getUUID(),
         minecraft.level.dimension().location(),
         callbackScope.sessionId(),
         currentConnection,
         sourceConnection
      )) {
         return false;
      }
      if (callbackConnection != currentConnection) {
         callbackConnection = currentConnection;
         callbackSessionId = callbackScope.sessionId();
         return true;
      }
      return callbackScope.sessionId().equals(callbackSessionId);
   }

   /** Accepts a server callback only after a snapshot established its current session. */
   public boolean acceptsCallback(OperationCallbackScope callbackScope, Connection sourceConnection) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
         return false;
      }
      Connection currentConnection = minecraft.getConnection().getConnection();
      return matchesOperationCallbackScope(
         callbackScope,
         minecraft.player.getUUID(),
         minecraft.level.dimension().location(),
         callbackSessionId,
         currentConnection,
         sourceConnection
      ) && callbackConnection == currentConnection
         && callbackScope.sessionId().equals(callbackSessionId);
   }

   /** Accepts an operation snapshot and binds its session to the current connection. */
   public boolean acceptsOperationPreviewCallback(OperationCallbackScope callbackScope, Connection sourceConnection) {
      return acceptsPreviewCallback(callbackScope, sourceConnection);
   }

   /** Accepts an operation result only after a snapshot established its current session. */
   public boolean acceptsOperationResultCallback(OperationCallbackScope callbackScope, Connection sourceConnection) {
      return acceptsCallback(callbackScope, sourceConnection);
   }

   /**
    * Accepts a receipt answer that belongs to the current connection.
    *
    * <p>The answer carries no callback scope. It answers a query that this client sent,
    * so only the connection must match. A late answer from a closed connection must
    * not change the state of the current one.</p>
    */
   public boolean acceptsReceiptCallback(Connection sourceConnection) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
         return false;
      }
      return sourceConnection != null && minecraft.getConnection().getConnection() == sourceConnection;
   }

   static boolean matchesOperationCallbackScope(
      OperationCallbackScope callbackScope,
      UUID playerId,
      ResourceLocation dimension,
      UUID sessionId,
      Object currentConnection,
      Object sourceConnection
   ) {
      return callbackScope != null
         && callbackScope.playerId().equals(playerId)
         && callbackScope.dimension().equals(dimension)
         && callbackScope.sessionId().equals(sessionId)
         && currentConnection != null
         && currentConnection == sourceConnection;
   }

   /**
    * Saves a submission receipt before the submission leaves the client.
    *
    * @return true when the receipt reached the disk. A false result means the client
    *         cannot promise a later recovery, so the caller must not send.
    */
   public boolean recordSubmissionReceipt(OperationSubmissionReceipt receipt) {
      if (currentScopeId() == null || receipt == null) return false;
      OperationSubmissionReceiptStore store = receiptStore(currentScopeId());
      if (!store.canAccept(receipt.transferId())) {
         // Every recorded submission still has work to do: a result to wait for, or a
         // cleanup that keeps an applied draft out of the editor. A new receipt would drop
         // one of those obligations, so this submission is refused with a message.
         LOGGER.warn(
            "The FastFormer submission receipts are at capacity ({} obligations); this submit was not sent",
            store.pendingObligationCount()
         );
         showDraftMessage("fastformer.message.operation_receipt_capacity_reached");
         // A failed write from an earlier call still belongs to this store.
         this.persistIfDirty(store);
         return false;
      }
      if (!store.record(receipt)) {
         // The store refused the record for a reason other than room. A receipt that this
         // client cannot record is a submission that it must not send.
         LOGGER.warn("The FastFormer submission receipt was refused; this submit was not sent");
         showDraftMessage("fastformer.message.operation_receipt_save_failed");
         return false;
      }
      // The dirty flag decides, not the write attempt. A caller that reads it after a
      // failed write learns the truth, and a later call retries the same write.
      return this.persistIfDirty(store);
   }

   /**
    * Writes one outcome change of a receipt.
    *
    * <p>A known applied result is final. A weaker answer, including an {@code UNKNOWN}
    * from a restarted server ledger, must not replace it. The change is refused, and the
    * applied record stays in place with its cleanup state.</p>
    *
    * @return true when the store holds the change and matches the disk.
    */
   public boolean updateSubmissionReceipt(UUID transferId, OperationSubmissionOutcome outcome, long nowMillis) {
      if (currentScopeId() == null || transferId == null || outcome == null) return false;
      OperationSubmissionReceiptStore store = receiptStore(currentScopeId());
      OperationSubmissionReceipt existing = store.find(transferId).orElse(null);
      boolean changed = existing != null
         && store.record(existing.withOutcome(outcome, nowMillis));
      // A failed write from an earlier call belongs to this store. Every later call flushes
      // it, even when this call changed nothing, so the pending write is never stranded.
      boolean durable = this.persistIfDirty(store);
      return changed && durable;
   }

   /**
    * Removes one receipt and writes the result.
    *
    * @return true when the store holds no such receipt and matches the disk
    */
   public boolean forgetSubmissionReceipt(UUID transferId) {
      if (currentScopeId() == null || transferId == null) return false;
      OperationSubmissionReceiptStore store = receiptStore(currentScopeId());
      boolean removed = store.remove(transferId);
      // An earlier call may have removed the receipt and then failed to write. This call
      // must retry that write, or the store stays dirty with no owner to flush it.
      boolean durable = this.persistIfDirty(store);
      return removed && durable;
   }

   /** Returns the recorded receipt of one submission for the current scope. */
   public Optional<OperationSubmissionReceipt> submissionReceipt(UUID transferId) {
      if (currentScopeId() == null || transferId == null) return Optional.empty();
      return receiptStore(currentScopeId()).find(transferId);
   }

   /** Returns every receipt of the current scope that still waits for a result. */
   public List<OperationSubmissionReceipt> openSubmissionReceipts() {
      return currentScopeId() == null ? List.of() : receiptStore(currentScopeId()).open();
   }

   /** Reads through the scope repository and reports each failed attempt once. */
   OperationSubmissionReceiptStore receiptStore(ClientScopeId key) {
      var repository = forScope(key).receipts();
      var read = repository.read();
      if (read.failure() != null) {
         LOGGER.warn("Unable to read the FastFormer submission receipts; the file stays", read.failure());
         showDraftMessage(repository.readState() == ReceiptReadState.VERSION_INCOMPATIBLE
            ? "fastformer.message.operation_receipt_version_incompatible"
            : "fastformer.message.operation_receipt_load_failed");
      } else if (read.newlyRead()) {
         this.rearmDraftReadAfterReceipt(key);
      }
      return read.store();
   }

   private boolean saveReceiptStore(ClientScopeId key, String failureKey) {
      try {
         forScope(key).receipts().save();
         return true;
      } catch (IOException | RuntimeException exception) {
         LOGGER.warn("Unable to save the FastFormer submission receipts", exception);
         showDraftMessage(failureKey);
         return false;
      }
   }

   public boolean retryCurrentReceiptLoad() {
      if (current == null || !current.receipts().retry()) return false;
      this.rearmDraftReadAfterReceipt(current.id());
      return true;
   }

   /**
    * Re-opens a draft read that waited for a readable receipt.
    *
    * <p>Only {@link ClientDraftLoadState#AWAITING_RECEIPT} clears. A completed read, a
    * recorded failure and a state the player ended by discarding the draft all stay, so this
    * call never returns a draft that the player already dismissed or replaced.</p>
    */
   private void rearmDraftReadAfterReceipt(ClientScopeId key) {
      if (key != null) forScope(key).drafts().rearmAfterReceipt();
   }

   private Path draftFileFor(ClientScopeId key) {
      return forScope(key).drafts().file();
   }

   private Path receiptFileFor(ClientScopeId key) {
      return forScope(key).receipts().file();
   }

   private void loadDraftOnce(Minecraft minecraft, ClientScope session) {
      if (minecraft.level == null || session == null || session.suspendedOperationDraft()) return;
      this.attemptDraftLoad(session, minecraft.level.registryAccess().lookupOrThrow(Registries.BLOCK));
   }

   /**
    * Reads one scope draft file at most once per recorded state.
    *
    * <p>A failure keeps the file and records why. Absence of a file completes the read.
    * {@link #retryCurrentDraftLoad()} is the only automatic-free way to read again.</p>
    *
    * <p>A draft whose submission already applied never reaches the staged slot. Clearing
    * the copies is not enough on its own, because a delete can fail or a file can be
    * unreadable. This check is the boundary that keeps applied work out of the editor.</p>
    */
   ClientDraftLoadState attemptDraftLoad(
      ClientScope session, HolderGetter<Block> blocks
   ) {
      var read = session.drafts().read(blocks, liveWorkBlocksDraftRead(session),
         transfer -> this.receiptVerdict(session.id(), transfer));
      if (read.failure() != null) {
         reportDraftLoadFailure(read.state(), read.failure());
      } else {
         switch (read.notice()) {
            case APPLIED -> showDraftMessage("fastformer.message.operation_draft_applied_not_restored");
            case AWAITING_RECEIPT -> showDraftMessage("fastformer.message.operation_draft_receipt_unknown_not_restored");
            case NONE -> {}
         }
      }
      if (read.draft() != null) {
         session.stageSuspendedOperationDraft(read.draft());
         reclaimScopesPending = true;
      }
      return read.state();
   }

   /**
    * True when this environment already holds live work that a staged draft would replace.
    *
    * <p>A durable copy that returns now would clear the workspace or the selection the player
    * is using. The copy therefore waits for a later boundary instead of overwriting live
    * work. The scope guard keeps the read out of the per-tick path.</p>
    */
   static boolean liveWorkBlocksDraftRead(ClientScope session) {
      return session != null
         && (!session.workspace().isEmpty() || session.selectionSession().hasDraft()
            || session.inputSession().blocksDraftLoad());
   }

   private static void reportDraftLoadFailure(ClientDraftLoadState state, Exception cause) {
      LOGGER.warn("Unable to read the FastFormer client operation draft: {}", state, cause);
      showDraftMessage(switch (state) {
         case RETRYABLE_FAILURE -> "fastformer.message.operation_draft_load_failed";
         case VERSION_INCOMPATIBLE -> "fastformer.message.operation_draft_version_incompatible";
         default -> "fastformer.message.operation_draft_corrupt_retained";
      });
   }

   private void deleteCurrentDraftFile() {
      if (currentScopeId() != null && !deleteDraftFile(currentScopeId())) {
         showDraftMessage("fastformer.message.operation_draft_delete_failed");
      }
   }

   private boolean deleteDraftFile(ClientScopeId key) {
      try {
         forScope(key).drafts().delete();
         reclaimScopesPending = true;
         return true;
      } catch (IOException exception) {
         LOGGER.warn("Unable to delete the FastFormer client operation draft", exception);
         return false;
      }
   }

   private static void showDraftMessage(String translationKey) {
      Minecraft minecraft = Minecraft.getInstance();
      // The client singleton is absent before bootstrap and after teardown. A draft message
      // must never break the scope bookkeeping that produced it.
      if (minecraft == null || minecraft.player == null) return;
      minecraft.player.displayClientMessage(Component.translatable(translationKey), false);
   }

   private static String connectionIdentity(Minecraft minecraft) {
      if (minecraft.getCurrentServer() != null) {
         return "remote:" + minecraft.getCurrentServer().ip.toLowerCase(Locale.ROOT);
      }
      if (minecraft.getSingleplayerServer() != null) {
         return "singleplayer:" + minecraft.getSingleplayerServer()
            .getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
      }
      return "connection:" + System.identityHashCode(minecraft.getConnection());
   }

}
