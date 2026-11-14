package io.github.fastformer.client.session;

import io.github.fastformer.common.mode.SessionActivity;
import io.github.fastformer.common.selection.OperationSelectionMode;

import com.mojang.logging.LogUtils;
import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.session.submission.ClientSubmissionPersistence;
import io.github.fastformer.client.session.draft.ClientDraftLoadState;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.ClientOperationDraftCodec;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.scope.ClientScopeId;
import io.github.fastformer.client.session.scope.ClientScopeRepository;
import io.github.fastformer.client.session.storage.ClientStorageQueue;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
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

   private final ClientScopeRepository scopes = new ClientScopeRepository();
   private final io.github.fastformer.client.session.preview.ClientPreviewState previews =
      new io.github.fastformer.client.session.preview.ClientPreviewState();

   public io.github.fastformer.client.session.preview.ClientPreviewState previews() { return previews; }

   public io.github.fastformer.client.geometry.pointer.GeometryPointer geometryPointer() {
      var input = inputSession();
      return input.geometryPointer(() -> new io.github.fastformer.client.geometry.pointer.GeometryPointer(
         previews::geometry, previews::geometryVersion, previews::geometryTransformVersion,
         () -> io.github.fastformer.client.interaction.intent.InteractionContext.capture(Minecraft.getInstance()),
         context -> pointerRay(input, context),
         () -> io.github.fastformer.client.input.PointerDragSnapshotView.geometryKey(input),
         (message, failure) -> LOGGER.warn(message, failure)));
   }

   public io.github.fastformer.client.quickshape.pointer.QuickShapePointer quickShapePointer() {
      var input = inputSession();
      return input.quickShapePointer(() -> new io.github.fastformer.client.quickshape.pointer.QuickShapePointer(
         previews::building, () -> io.github.fastformer.client.interaction.intent.InteractionContext.capture(Minecraft.getInstance()),
         context -> pointerRay(input, context)));
   }

   public boolean operationActive() {
      var operation = operations();
      return operation.active() || operation.selectionSessionActive();
   }

   public boolean usesScrollContext() {
      if (previews.geometry().active()) return geometryPointer().allows(io.github.fastformer.common.geometry.GeometryAction.SCALAR_ADJUST);
      return !previews.operation().active() && io.github.fastformer.client.quickshape.pointer.QuickShapeModes.scrollContext(previews.building());
   }

   public io.github.fastformer.client.interaction.path.PathCloseTarget pathCloseTarget() {
      var c = io.github.fastformer.client.interaction.intent.InteractionContext.capture(Minecraft.getInstance());
      var operation = previews.operation();
      var geometry = previews.geometry();
      var operationPath = operation.active() && operation.operationSelectionMode() == io.github.fastformer.common.selection.OperationSelectionMode.PRISM
         && operation.operationPrismBasePointCount() == 0 ? operation.points() : java.util.List.<BlockPos>of();
      var geometryPath = geometry.active() && geometry.mode() == io.github.fastformer.common.geometry.GeometryMode.WALL
         && !geometry.closed() ? geometry.points() : java.util.List.<BlockPos>of();
      BlockPos candidate = null;
      if (c != null) {
         if (geometry.active()) { var hit = geometryPointer().candidateHit(); candidate = hit == null ? null : hit.point(); }
         else if (operation.active()) candidate = operations().remotePointer().operationCandidatePoint();
         else if (previews.building().active()) candidate = quickShapePointer().candidate();
      }
      return io.github.fastformer.client.interaction.path.PathCloseTarget.resolve(operationPath, geometryPath, quickShapePointer().openPath(),
         c == null ? null : c.eye(), c == null ? null : c.view(), c == null ? null : pointerRay(inputSession(), c), candidate);
   }

   /** Samples the established ray purpose for this input owner's environment. */
   public io.github.fastformer.common.raycast.LongRangeBlockRaycast.Result pointerRay(
      io.github.fastformer.client.input.ClientInputSession input,
      io.github.fastformer.client.interaction.intent.InteractionContext context) {
      var operations = operations();
      var hand = context.player().getMainHandItem();
      var policy = io.github.fastformer.client.interaction.pointer.PointerRayPolicy.select(
         previews.geometry().active(), previews.building().active(), operations.active(),
         operations.selectionSessionActive() || operations.selectionEdits().selectionDraftActive(),
         hand.isEmpty(), io.github.fastformer.common.placement.context.PlaceableItems.isPlaceable(hand));
      return input.pointer().sample(context.player(), context.alternative(), policy);
   }
   private final io.github.fastformer.client.session.preview.ClientPreviewUpdates previewUpdates =
      new io.github.fastformer.client.session.preview.ClientPreviewUpdates(previews,
         payload -> operations().synchronize(payload), activity -> operations().submissions().observeActivity(activity));

   public io.github.fastformer.client.session.preview.ClientPreviewUpdates previewUpdates() { return previewUpdates; }

   /** Captures workflow facts from their owners without querying the renderer. */
   public io.github.fastformer.client.mode.ClientModeView modeView() {
      var operations = operations();
      var input = inputSession();
      var observation = new io.github.fastformer.client.mode.ModeObservation(
         previews.activity() == io.github.fastformer.common.mode.SessionActivity.RESTORE_TASK,
         previews.activity().task(),
         operations.active() || operations.selectionSessionActive(),
         operations.active(), operations.operationSelectionConfirmed(),
         previews.geometry().active(), previews.building().active(),
         io.github.fastformer.client.placement.QuickReplaceMode.active(), input.smartFill().active());
      return input.modeView(observation);
   }
   private boolean reclaimScopesPending;
   private Object callbackConnection;
   private UUID callbackSessionId;
   private final ClientStorageQueue storage = new ClientStorageQueue();
   private final ClientSubmissionPersistence submissions = new ClientSubmissionPersistence(storage, scopes::current,
      (plan, id) -> ClientPlacementRouter.prepareWorkspace(plan, id)::send,
      () -> reclaimScopesPending = true, (key, failure) -> {
         LOGGER.warn("FastFormer client persistence failure: {}", key, failure);
         if (key != null) showDraftMessage(key);
      }, System::currentTimeMillis, System::nanoTime);

   public ClientSubmissionPersistence submissions() { return submissions; }

   public void drainStorageResults() {
      storage.drain();
      submissions.tick();
      if (reclaimScopesPending && storage.idle() && submissions.idle()) {
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
      return scopes.current();
   }

   public io.github.fastformer.client.operation.controller.OperationContext operations() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft != null && minecraft.player != null) observePlayer(minecraft);
      return scopes.operations();
   }

   private ClientScopeId currentScopeId() {
      return scopes.current() == null ? null : scopes.current().id();
   }

   public io.github.fastformer.client.input.ClientInputSession inputSession() {
      return scopes.inputSession();
   }

   public io.github.fastformer.client.operation.selection.ClientSelectionSession selectionSession() {
      return scopes.selectionSession();
   }

   public void clearUnboundInteraction() {
      scopes.clearUnboundInteraction();
   }

   public ClientScope forCurrent(Minecraft minecraft) {
      observePlayer(minecraft);
      return scopes.current();
   }

   /** Returns the session isolated to one connection, player, and dimension. */
   public ClientScope forScope(ClientScopeId key) {
      return scopes.resolve(key);
   }

   ClientScope activateScope(UUID playerId, String connection, String dimension) {
      var previous = scopes.current();
      var next = scopes.activate(new ClientScopeId(connection, dimension, playerId));
      if (previous != null && previous != next) {
         reclaimScopesPending = true;
         reclaimInactiveScopes();
      }
      return next;
   }

   void reclaimInactiveScopes() {
      if (!storage.idle() || !submissions.idle()) return;
      reclaimScopesPending = false;
      scopes.reclaimInactive();
   }

   /** Records the current player identity without coupling the session box to LocalPlayer lifetime. */
   public void observePlayer(Minecraft minecraft) {
      storage.drain();
      if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
         return;
      }
      previews.observeFacePolicies(minecraft.player.registryAccess());
      String connection = connectionIdentity(minecraft);
      String dimension = minecraft.level == null ? "" : minecraft.level.dimension().location().toString();
      ClientScope session = activateScope(minecraft.player.getUUID(), connection, dimension);
      // The cleanup runs first. An applied draft must never return to the editor, because
      // the world already holds its result. The read below would stage that draft.
      if (session.claimCleanupReplay()) {
         submissions.replayPendingCleanups();
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
      if (scopes.current() == null || currentScopeId() == null) return;
      scopes.current().suspendOperationDraft(identity, originWhenNoIdentity, submissionId);
      ClientOperationDraft draft = scopes.current().suspendedOperationDraftData();
      if (draft == null) return;
      var owner = scopes.current();
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
      if (scopes.current() == null) return false;
      boolean hadStagedDraft = scopes.current().suspendedOperationDraft();
      boolean restored = scopes.current().restoreSuspendedOperationDraft(identity);
      if (!hadStagedDraft) return false;
      if (restored) {
         // The draft returned to the editor, so its durable copy is spent.
         this.deleteCurrentDraftFile();
         if (currentScopeId() != null) {
            scopes.current().drafts().markReady();
         }
         return true;
      }
      // The server selection changed, so the draft cannot return now. Keep the file and
      // record a retryable state: entering this environment again reads the file once
      // more. Deleting it here destroyed the only remaining copy.
      if (currentScopeId() != null) {
         scopes.current().drafts().restoreRejected();
      }
      showDraftMessage("fastformer.message.operation_draft_restore_kept");
      return false;
   }

   /**
    * Clears the staged draft of the current scope and its durable file.
    *
    * <p>Only an explicit player discard uses this path. A submission result uses
    * {@link ClientSubmissionPersistence#settleAppliedSubmission(UUID)} examines each owner separately.</p>
    */
   public void discardCurrentDraft() {
      if (scopes.current() != null) scopes.current().discardSuspendedOperationDraft();
      this.deleteCurrentDraftFile();
      if (currentScopeId() != null) {
         scopes.current().drafts().markReady();
      }
   }

   /**
    * Clears a failed read of the current scope so the next scope tick reads the file again.
    *
    * <p>This is the explicit retry boundary. A failed read never repeats on its own and
    * never deletes the file.
    */
   public boolean retryCurrentDraftLoad() {
      if (currentScopeId() == null) return false;
      return scopes.current().drafts().retry();
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
      return scopes.current() != null && scopes.current().suspendedOperationDraft();
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
      var read = session.drafts().read(blocks, session.hasLiveWork(),
         transfer -> submissions.verdict(session, transfer));
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
