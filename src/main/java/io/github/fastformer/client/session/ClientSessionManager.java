package io.github.fastformer.client.session;

import com.mojang.logging.LogUtils;
import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Owns top-level client session transitions and keeps them out of event handlers. */
public final class ClientSessionManager {
   private static final ClientSessionManager INSTANCE = new ClientSessionManager();
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final String DRAFT_DIRECTORY = "fastformer/session-drafts";
   private static final String RECEIPT_DIRECTORY = "fastformer/session-receipts";

   private final Map<SessionKey, ClientPlayerSession> playerSessions = new HashMap<>();
   /** Explicit read result for each scope draft file. Absent means NOT_LOADED. */
   private final Map<SessionKey, ClientDraftLoadState> draftLoadStates = new HashMap<>();
   /** Submission receipts of each scope, read from disk at the first use. */
   private final Map<SessionKey, OperationSubmissionReceiptStore> receiptStores = new HashMap<>();
   private final Map<SessionKey, ClientDraftLoadState> receiptLoadStates = new HashMap<>();
   /** Receipt files of one scope. The read path uses this, and a test supplies its own path. */
   private final Map<SessionKey, Path> receiptFiles = new HashMap<>();
   /** Draft files of one scope. The read, save and delete paths use this, and a test supplies its own path. */
   private final Map<SessionKey, Path> draftFiles = new HashMap<>();
   /** Scopes whose applied-draft cleanup already ran in this process. */
   private final java.util.Set<SessionKey> cleanupReplayed = new java.util.HashSet<>();
   private ClientPlayerSession current;
   private SessionKey currentKey;
   private Object callbackConnection;
   private UUID callbackSessionId;

   private ClientSessionManager() {
   }

   public static ClientSessionManager instance() {
      return INSTANCE;
   }

   /** Returns the most recently active player session, including across disconnects. */
   public ClientPlayerSession currentSession() {
      return current;
   }

   public ClientPlayerSession forCurrent(Minecraft minecraft) {
      observePlayer(minecraft);
      return current;
   }

   /** Returns the persistent session box for a player identity. */
   public ClientPlayerSession forPlayer(UUID playerId) {
      if (playerId == null) {
         throw new IllegalArgumentException("playerId must not be null");
      }
      return playerSessions.computeIfAbsent(new SessionKey("legacy", "", playerId), key -> new ClientPlayerSession(playerId));
   }

   /** Returns the session isolated to one connection, player, and dimension. */
   public ClientPlayerSession forScope(UUID playerId, String connection, String dimension) {
      if (playerId == null) throw new IllegalArgumentException("playerId must not be null");
      SessionKey key = new SessionKey(connection, dimension, playerId);
      return playerSessions.computeIfAbsent(key, ignored -> new ClientPlayerSession(playerId));
   }

   ClientPlayerSession activateScope(UUID playerId, String connection, String dimension) {
      SessionKey nextKey = new SessionKey(connection, dimension, playerId);
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
      if (scopeChanged
         && this.draftLoadStates.getOrDefault(nextKey, ClientDraftLoadState.NOT_LOADED)
            == ClientDraftLoadState.RETRYABLE_FAILURE) {
         // Entering an environment again is an explicit boundary, so a transient read
         // failure may be read once more. A bad version or content stays recorded, and the
         // repeated call for an unchanged scope never retries.
         this.draftLoadStates.remove(nextKey);
      }
      if (scopeChanged
         && this.receiptLoadStates.getOrDefault(nextKey, ClientDraftLoadState.NOT_LOADED)
            == ClientDraftLoadState.RETRYABLE_FAILURE) {
         // The same boundary serves the receipt file. The cached store came from the failed
         // read, so it leaves with the state and the next use reads the file again. A
         // version mismatch stays recorded, because only a newer client can read it.
         this.recordReceiptLoadStateCleared(nextKey);
      }
      currentKey = nextKey;
      if (scopeChanged || firstActivation) {
         // Every entry into an environment is a cleanup boundary. The applied drafts of
         // this scope are cleared once more before the draft file is read.
         this.cleanupReplayed.remove(nextKey);
      }
      ClientPlayerSession next = playerSessions.computeIfAbsent(nextKey, ignored -> new ClientPlayerSession(playerId));
      if (scopeChanged || firstActivation) next.resetInput();
      return current = next;
   }

   /** Records the current player identity without coupling the session box to LocalPlayer lifetime. */
   public void observePlayer(Minecraft minecraft) {
      if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
         return;
      }
      String connection = connectionIdentity(minecraft);
      String dimension = minecraft.level == null ? "" : minecraft.level.dimension().location().toString();
      ClientPlayerSession session = activateScope(minecraft.player.getUUID(), connection, dimension);
      // The cleanup runs first. An applied draft must never return to the editor, because
      // the world already holds its result. The read below would stage that draft.
      if (this.currentKey != null && this.cleanupReplayed.add(this.currentKey)) {
         this.replayPendingCleanups();
      }
      this.loadDraftOnce(minecraft, this.currentKey, session);
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
      if (this.current == null || this.currentKey == null) return;
      this.current.suspendOperationDraft(identity, originWhenNoIdentity, submissionId);
      ClientOperationDraft draft = this.current.suspendedOperationDraftData();
      if (draft == null) return;
      try {
         OperationClipboardStore.save(draftFileFor(this.currentKey), ClientOperationDraftCodec.encode(draft));
         // The scope now owns a known draft in memory and on disk. No read is pending.
         this.draftLoadStates.put(this.currentKey, ClientDraftLoadState.READY);
      } catch (IOException | RuntimeException exception) {
         LOGGER.warn("Unable to save the FastFormer client operation draft", exception);
         showDraftMessage("fastformer.message.operation_draft_save_failed");
      }
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
         if (this.currentKey != null) {
            this.draftLoadStates.put(this.currentKey, ClientDraftLoadState.READY);
         }
         return true;
      }
      // The server selection changed, so the draft cannot return now. Keep the file and
      // record a retryable state: entering this environment again reads the file once
      // more. Deleting it here destroyed the only remaining copy.
      if (this.currentKey != null) {
         this.draftLoadStates.put(this.currentKey, ClientDraftLoadState.RETRYABLE_FAILURE);
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
    * @return true when the draft reached the disk
    */
   public boolean persistSubmittedDraft(
      OperationDraftIdentity identity, OperationSubmissionOrigin originWhenNoIdentity, UUID transferId
   ) {
      if (this.current == null || this.currentKey == null || transferId == null) return false;
      ClientOperationDraft draft =
         this.current.buildSubmittedDraft(identity, originWhenNoIdentity, transferId);
      if (draft == null) return false;
      try {
         OperationClipboardStore.save(draftFileFor(this.currentKey), ClientOperationDraftCodec.encode(draft));
         this.draftLoadStates.put(this.currentKey, ClientDraftLoadState.READY);
         return true;
      } catch (IOException | RuntimeException exception) {
         LOGGER.warn("Unable to save the FastFormer client operation draft before a submission", exception);
         showDraftMessage("fastformer.message.operation_draft_save_failed");
         return false;
      }
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
      if (this.currentKey != null) {
         this.draftLoadStates.put(this.currentKey, ClientDraftLoadState.READY);
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
      if (this.currentKey == null) return OperationDraftSettlement.DurableProbe.absent();
      try {
         Optional<CompoundTag> root = OperationClipboardStore.loadStrict(draftFileFor(this.currentKey));
         if (root.isEmpty()) return OperationDraftSettlement.DurableProbe.absent();
         return OperationDraftSettlement.DurableProbe.read(
            ClientOperationDraftCodec.readSubmissionId(root.get())
         );
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
            if (this.current != null) this.current.discardSuspendedOperationDraft();
         }
      );
      OperationDraftSettlement.Ownership durable = OperationDraftSettlement.resolveDurable(
         transferId,
         this.probeDurableDraft(),
         () -> this.currentKey != null && deleteDraftFile(this.currentKey)
      );
      return OperationDraftSettlement.combine(staged, durable);
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
      if (this.currentKey == null || transferId == null) {
         return new OperationDraftSettlement.Applied(
            OperationDraftSettlement.combine(
               OperationDraftSettlement.Ownership.UNCONFIRMED,
               OperationDraftSettlement.Ownership.UNCONFIRMED
            ),
            false
         );
      }
      OperationSubmissionReceiptStore store = receiptStore(this.currentKey);
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
      if (this.currentKey == null || !store.dirty()) {
         return !store.dirty();
      }
      saveReceiptStore(this.currentKey, store, "fastformer.message.operation_receipt_save_failed");
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
      if (this.currentKey == null) return 0;
      OperationSubmissionReceiptStore store = receiptStore(this.currentKey);
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

   /** What the receipt file says about one submission. */
   enum ReceiptVerdict {
      /** The receipt file names this submission as applied. Its draft must not return. */
      APPLIED,
      /** The receipt file names no applied result for this submission. */
      NOT_APPLIED,
      /** The receipt file could not be read, so the client cannot prove either way. */
      UNKNOWN
   }

   /**
    * Reads what the receipt file says about one submission.
    *
    * <p>A file that cannot be read is not the same as a file with no entry. The file may
    * hold an applied result for this very submission, so the answer is unknown and the
    * client must not act as if the submission were new.</p>
    *
    * @param receiptStoreOverride the store to consult, or null to use the store of the key
    */
   ReceiptVerdict receiptVerdict(
      SessionKey key, UUID transferId, OperationSubmissionReceiptStore receiptStoreOverride
   ) {
      if (transferId == null) {
         // A draft that names no submission has no receipt to answer for it.
         return ReceiptVerdict.NOT_APPLIED;
      }
      OperationSubmissionReceiptStore store = receiptStoreOverride;
      if (store == null) {
         if (key == null) return ReceiptVerdict.UNKNOWN;
         store = receiptStore(key);
      }
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
      if (this.currentKey == null) return false;
      if (!this.draftLoadState(this.currentKey).mayRetry()) return false;
      this.draftLoadStates.remove(this.currentKey);
      return true;
   }

   public ClientDraftLoadState currentDraftLoadState() {
      return this.currentKey == null ? ClientDraftLoadState.NOT_LOADED : this.draftLoadState(this.currentKey);
   }

   ClientDraftLoadState draftLoadState(SessionKey key) {
      return key == null
         ? ClientDraftLoadState.NOT_LOADED
         : this.draftLoadStates.getOrDefault(key, ClientDraftLoadState.NOT_LOADED);
   }

   public boolean currentDraftPending() {
      return this.current != null && this.current.suspendedOperationDraft();
   }

   /** Accepts a server snapshot and binds its callback session to the current connection. */
   public boolean acceptsPreviewCallback(OperationCallbackScope callbackScope, Connection sourceConnection) {      Minecraft minecraft = Minecraft.getInstance();
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
      if (this.currentKey == null || receipt == null) return false;
      OperationSubmissionReceiptStore store = receiptStore(this.currentKey);
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
      if (this.currentKey == null || transferId == null || outcome == null) return false;
      OperationSubmissionReceiptStore store = receiptStore(this.currentKey);
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
      if (this.currentKey == null || transferId == null) return false;
      OperationSubmissionReceiptStore store = receiptStore(this.currentKey);
      boolean removed = store.remove(transferId);
      // An earlier call may have removed the receipt and then failed to write. This call
      // must retry that write, or the store stays dirty with no owner to flush it.
      boolean durable = this.persistIfDirty(store);
      return removed && durable;
   }

   /** Returns the recorded receipt of one submission for the current scope. */
   public Optional<OperationSubmissionReceipt> submissionReceipt(UUID transferId) {
      if (this.currentKey == null || transferId == null) return Optional.empty();
      return receiptStore(this.currentKey).find(transferId);
   }

   /** Returns every receipt of the current scope that still waits for a result. */
   public List<OperationSubmissionReceipt> openSubmissionReceipts() {
      return this.currentKey == null ? List.of() : receiptStore(this.currentKey).open();
   }

   /** Reports the receipt store of one scope and records a failed read. */
   OperationSubmissionReceiptStore receiptStore(SessionKey key) {
      OperationSubmissionReceiptStore cached = this.receiptStores.get(key);
      if (cached != null) return cached;
      OperationSubmissionReceiptStore store;
      try {
         store = OperationSubmissionReceiptStore.load(receiptFileFor(key));
      } catch (OperationSubmissionReceiptCodec.VersionMismatchException exception) {
         this.recordReceiptLoadState(key, ClientDraftLoadState.VERSION_INCOMPATIBLE);
         LOGGER.warn("Unsupported FastFormer submission receipt version; the file stays", exception);
         showDraftMessage("fastformer.message.operation_receipt_version_incompatible");
         store = OperationSubmissionReceiptStore.unreadable();
      } catch (IOException | RuntimeException exception) {
         // The read may fail for a reason that a later boundary can clear, such as a file
         // that another process held. The state therefore allows one retry, and the client
         // keeps the file either way.
         this.recordReceiptLoadState(key, ClientDraftLoadState.RETRYABLE_FAILURE);
         LOGGER.warn("Unable to read the FastFormer submission receipts; the file stays", exception);
         showDraftMessage("fastformer.message.operation_receipt_load_failed");
         store = OperationSubmissionReceiptStore.unreadable();
      }
      this.receiptStores.put(key, store);
      if (!store.readOnly()) {
         // The receipt answer is available now, so a draft that waited for it may be read and
         // judged again at the next draft pass.
         this.rearmDraftReadAfterReceipt(key);
      }
      return store;
   }

   private boolean saveReceiptStore(SessionKey key, OperationSubmissionReceiptStore store, String failureKey) {
      try {
         store.save(receiptFileFor(key));
         this.recordReceiptLoadState(key, ClientDraftLoadState.READY);
         return true;
      } catch (IOException | RuntimeException exception) {
         LOGGER.warn("Unable to save the FastFormer submission receipts", exception);
         // The player must learn that this submission cannot be recovered later.
         showDraftMessage(failureKey);
         return false;
      }
   }

   /** The recorded read result of one scope receipt file. */
   ClientDraftLoadState receiptLoadState(SessionKey key) {
      return key == null
         ? ClientDraftLoadState.NOT_LOADED
         : this.receiptLoadStates.getOrDefault(key, ClientDraftLoadState.NOT_LOADED);
   }

   /** Records the read result of one scope receipt file. */
   void recordReceiptLoadState(SessionKey key, ClientDraftLoadState state) {
      if (key != null && state != null) {
         this.receiptLoadStates.put(key, state);
      }
   }

   /** The recorded read result of one scope receipt file. */
   public ClientDraftLoadState currentReceiptLoadState() {
      return this.receiptLoadState(this.currentKey);
   }

   /**
    * Clears a failed receipt read of the current scope so the next use reads the file again.
    *
    * <p>This is the explicit retry boundary for the receipt file, the same way
    * {@link #retryCurrentDraftLoad()} serves the draft file. A failed read stays recorded
    * until an explicit boundary, so a damaged file is read once and not on every tick.</p>
    */
   public boolean retryCurrentReceiptLoad() {
      if (this.currentKey == null) return false;
      if (!this.receiptLoadState(this.currentKey).mayRetry()) return false;
      this.recordReceiptLoadStateCleared(this.currentKey);
      return true;
   }

   private void recordReceiptLoadStateCleared(SessionKey key) {
      this.receiptLoadStates.remove(key);
      // The cached store came from the failed read. Drop it, so the next use reads again.
      OperationSubmissionReceiptStore cached = this.receiptStores.get(key);
      if (cached != null && cached.readOnly()) {
         this.receiptStores.remove(key);
      }
      // The receipt read is about to be attempted again, so a draft that waited for an answer
      // may also be read and judged again.
      this.rearmDraftReadAfterReceipt(key);
   }

   /**
    * Re-opens a draft read that waited for a readable receipt.
    *
    * <p>Only {@link ClientDraftLoadState#AWAITING_RECEIPT} clears. A completed read, a
    * recorded failure and a state the player ended by discarding the draft all stay, so this
    * call never returns a draft that the player already dismissed or replaced.</p>
    */
   private void rearmDraftReadAfterReceipt(SessionKey key) {
      if (key != null && this.draftLoadState(key) == ClientDraftLoadState.AWAITING_RECEIPT) {
         this.draftLoadStates.remove(key);
      }
   }

   /** Uses an explicit receipt file for one scope. The read path uses this. */
   void installReceiptFile(SessionKey key, Path file) {
      if (key != null && file != null) {
         this.receiptFiles.put(key, file);
      }
   }

   /**
    * Uses an explicit draft file for one scope.
    *
    * <p>The read, save and delete paths all use this, so a caller without a game directory
    * can drive the real draft flow instead of a copy of it.</p>
    */
   void installDraftFile(SessionKey key, Path file) {
      if (key != null && file != null) {
         this.draftFiles.put(key, file);
      }
   }

   private Path draftFileFor(SessionKey key) {
      Path override = key == null ? null : this.draftFiles.get(key);
      return override != null ? override : draftFile(key);
   }

   private Path receiptFileFor(SessionKey key) {
      Path override = key == null ? null : this.receiptFiles.get(key);
      return override != null ? override : receiptFile(key);
   }

   private void loadDraftOnce(Minecraft minecraft, SessionKey key, ClientPlayerSession session) {
      if (minecraft.level == null || key == null || session.suspendedOperationDraft()) return;
      this.attemptDraftLoad(
         key,
         session,
         minecraft.level.registryAccess().lookupOrThrow(Registries.BLOCK),
         draftFileFor(key)
      );
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
      SessionKey key, ClientPlayerSession session, HolderGetter<Block> blocks, Path file
   ) {
      return this.attemptDraftLoad(key, session, blocks, file, null);
   }

   /**
    * Reads one scope draft file against an explicit receipt store.
    *
    * @param receiptStoreOverride the store to consult, or null to use the store of the scope
    */
   ClientDraftLoadState attemptDraftLoad(
      SessionKey key, ClientPlayerSession session, HolderGetter<Block> blocks, Path file,
      OperationSubmissionReceiptStore receiptStoreOverride
   ) {
      ClientDraftLoadState recorded = this.draftLoadState(key);
      if (recorded.blocksRead()) {
         return recorded;
      }
      if (liveWorkBlocksDraftRead(session)) {
         // The player already built work in this environment. Staging the durable copy would
         // clear that work, so the copy waits. No state is recorded and no file is read, so
         // the check costs nothing per tick and the read happens once that work is gone.
         return ClientDraftLoadState.NOT_LOADED;
      }
      CompoundTag root;
      try {
         root = OperationClipboardStore.loadStrict(file).orElse(null);
      } catch (IOException | RuntimeException exception) {
         return this.recordDraftLoadFailure(key, ClientDraftLoadState.RETRYABLE_FAILURE, exception);
      }
      if (root == null) {
         this.draftLoadStates.put(key, ClientDraftLoadState.READY);
         return ClientDraftLoadState.READY;
      }
      ClientOperationDraft draft;
      try {
         draft = ClientOperationDraftCodec.decode(root, blocks);
      } catch (ClientOperationDraftCodec.VersionMismatchException exception) {
         return this.recordDraftLoadFailure(key, ClientDraftLoadState.VERSION_INCOMPATIBLE, exception);
      } catch (IOException | RuntimeException exception) {
         return this.recordDraftLoadFailure(key, ClientDraftLoadState.CORRUPT, exception);
      }
      ReceiptVerdict verdict = this.receiptVerdict(key, draft.submissionId(), receiptStoreOverride);
      if (verdict == ReceiptVerdict.APPLIED) {
         // The server already wrote this work. The draft never returns to the editor, and a
         // later receipt answer cannot change that, so this read is complete.
         this.draftLoadStates.put(key, ClientDraftLoadState.READY);
         LOGGER.warn("The FastFormer draft of an applied submission was not staged");
         showDraftMessage("fastformer.message.operation_draft_applied_not_restored");
         return ClientDraftLoadState.READY;
      }
      if (verdict == ReceiptVerdict.UNKNOWN) {
         // The receipt file cannot prove either way. This is not a completed read: the draft
         // stays out of the editor until a readable receipt answers, and the same file is
         // then read and judged again. The file stays, because its cleanup may still be
         // unconfirmed.
         this.draftLoadStates.put(key, ClientDraftLoadState.AWAITING_RECEIPT);
         LOGGER.warn("The FastFormer draft waits for a readable submission receipt");
         showDraftMessage("fastformer.message.operation_draft_receipt_unknown_not_restored");
         return ClientDraftLoadState.AWAITING_RECEIPT;
      }
      session.stageSuspendedOperationDraft(draft);
      this.draftLoadStates.put(key, ClientDraftLoadState.READY);
      return ClientDraftLoadState.READY;
   }

   /**
    * True when this environment already holds live work that a staged draft would replace.
    *
    * <p>A durable copy that returns now would clear the workspace or the selection the player
    * is using. The copy therefore waits for a later boundary instead of overwriting live
    * work. The scope guard keeps the read out of the per-tick path.</p>
    */
   static boolean liveWorkBlocksDraftRead(ClientPlayerSession session) {
      return session != null
         && (!session.operationWorkspace().isEmpty() || session.selectionSession().hasDraft()
            || session.inputSession().blocksDraftLoad());
   }

   private ClientDraftLoadState recordDraftLoadFailure(
      SessionKey key, ClientDraftLoadState state, Exception cause
   ) {
      this.draftLoadStates.put(key, state);
      LOGGER.warn("Unable to read the FastFormer client operation draft: {}", state, cause);
      showDraftMessage(switch (state) {
         case RETRYABLE_FAILURE -> "fastformer.message.operation_draft_load_failed";
         case VERSION_INCOMPATIBLE -> "fastformer.message.operation_draft_version_incompatible";
         default -> "fastformer.message.operation_draft_corrupt_retained";
      });
      return state;
   }

   private void deleteCurrentDraftFile() {
      if (this.currentKey != null && !deleteDraftFile(this.currentKey)) {
         showDraftMessage("fastformer.message.operation_draft_delete_failed");
      }
   }

   private static boolean deleteDraftFileAt(Path file) {
      try {
         Files.deleteIfExists(file);
         return true;
      } catch (IOException exception) {
         LOGGER.warn("Unable to delete the FastFormer client operation draft", exception);
         return false;
      }
   }

   private boolean deleteDraftFile(SessionKey key) {
      return deleteDraftFileAt(draftFileFor(key));
   }

   private static void showDraftMessage(String translationKey) {
      Minecraft minecraft = Minecraft.getInstance();
      // The client singleton is absent before bootstrap and after teardown. A draft message
      // must never break the scope bookkeeping that produced it.
      if (minecraft == null || minecraft.player == null) return;
      minecraft.player.displayClientMessage(Component.translatable(translationKey), false);
   }

   private static Path draftFile(SessionKey key) {
      String scope = key.connection() + "\n" + key.dimension() + "\n" + key.playerId();
      return FMLPaths.CONFIGDIR.get().resolve(DRAFT_DIRECTORY).resolve(sha256(scope) + ".nbt.gz");
   }

   private static Path receiptFile(SessionKey key) {
      String scope = key.connection() + "\n" + key.dimension() + "\n" + key.playerId();
      return FMLPaths.CONFIGDIR.get().resolve(RECEIPT_DIRECTORY).resolve(sha256(scope) + ".nbt.gz");
   }

   private static String sha256(String value) {
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
         return java.util.HexFormat.of().formatHex(digest);
      } catch (NoSuchAlgorithmException exception) {
         throw new IllegalStateException("SHA-256 is not available", exception);
      }
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

   record SessionKey(String connection, String dimension, UUID playerId) {
      SessionKey {
         connection = connection == null ? "" : connection;
         dimension = dimension == null ? "" : dimension;
      }
   }
}
