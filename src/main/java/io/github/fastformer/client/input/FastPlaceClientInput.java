package io.github.fastformer.client.input;


import com.mojang.blaze3d.platform.InputConstants;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.render.interaction.OperationPointerKind;
import io.github.fastformer.client.render.interaction.OperationPointerTarget;
import io.github.fastformer.client.input.math.ClientInputMath;
import io.github.fastformer.client.input.drag.DeferredDragClick;
import io.github.fastformer.client.input.drag.DragAxisFrame;
import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.GizmoDragCalculator;
import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.client.input.drag.OperationPointDragCalculator;
import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.session.ClientSessionManager;
import io.github.fastformer.client.ui.GeometryRadialScreen;
import io.github.fastformer.network.payload.geometry.CycleStageModePayload;
import io.github.fastformer.network.payload.geometry.ClosePathPayload;
import io.github.fastformer.network.payload.settings.ModifierStatePayload;
import io.github.fastformer.network.payload.geometry.GeometryInteractionPayload;
import io.github.fastformer.network.payload.operation.OperationExtendPayload;
import io.github.fastformer.network.payload.operation.OperationTransformPayload;
import io.github.fastformer.network.payload.placement.QuitFastPlacePayload;
import io.github.fastformer.network.payload.operation.OperationPointPayload;
import io.github.fastformer.network.payload.operation.OperationPointDragPayload;
import io.github.fastformer.network.payload.operation.OperationInsertPointPayload;
import io.github.fastformer.network.payload.operation.OperationRemovePointPayload;
import io.github.fastformer.network.payload.operation.OperationSelectPointPayload;
import io.github.fastformer.network.payload.geometry.ScrollCandidatePayload;
import io.github.fastformer.network.payload.placement.UndoFastPlacePayload;
import io.github.fastformer.network.payload.world.WorldRedoPayload;
import io.github.fastformer.network.payload.world.WorldUndoPayload;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.client.interaction.SelectionDragCapture;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.PlaceableItems;
import io.github.fastformer.fastplace.OperationPointDragConstraint;
import io.github.fastformer.fastplace.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.InputEvent.Key;
import net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre;
import net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@EventBusSubscriber(
   modid = "fastformer",
   value = {Dist.CLIENT}
)
public final class FastPlaceClientInput {
   private static final double OPERATION_REACH = LongRangeBlockRaycast.MAX_REACH;
   private static final int MAX_DRAG_STEPS_PER_PACKET = 128;
   private static final long MODIFIER_SHORT_PRESS_NANOS = 250_000_000L;
   private static final long UNDO_SHORT_PRESS_NANOS = 250_000_000L;
   static final long OPERATION_FACE_SHORT_PRESS_NANOS = 140_000_000L;
   private static final long OPERATION_POINT_SHORT_PRESS_NANOS = 250_000_000L;
   private static final long OPERATION_POINT_DOUBLE_CLICK_NANOS = 350_000_000L;
   private static final ClientInputSession UNBOUND_INPUT = new ClientInputSession();

   static ClientInputSession inputSession() {
      var owner = ClientSessionManager.instance().currentSession();
      return owner == null ? UNBOUND_INPUT : owner.inputSession();
   }

   private FastPlaceClientInput() {
   }

   public static void endWorldSession() {
      io.github.fastformer.network.client.ClientPayloadDispatcher.endWorldSession();
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft != null && inputSession().operationSessionWasActive) minecraft.options.keyAttack.setDown(false);
      cancelOperationGesture(minecraft);
      inputSession().reset();
      QuickReplaceMode.clear();
   }

   public static boolean beginPlacementRequest(long requestId) {
      synchronizeInputState();
      if (!inputSession().routing.submit(requestId)) {
         return false;
      }
      cancelOperationGesture(Minecraft.getInstance());
      return true;
   }

   public static boolean awaitsPlacementRequest(long requestId) {
      return inputSession().routing.awaitsPlacementRequest(requestId);
   }

   public static void acknowledgePlacementRequest(
      io.github.fastformer.network.payload.placement.PlacementActionAckPayload payload
   ) {
      var observed = observedInputState();
      inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(payload.requestId()),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, observed));
   }

   public static void abortPlacementRequest(long requestId) {
      var observed = observedInputState();
      inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(requestId),
         ClientInputStateMachine.SubmissionEvent.FAILED, observed));
   }

   public static boolean beginWorkspaceRequest(java.util.UUID transferId) {
      synchronizeInputState();
      if (!inputSession().routing.submit(transferId)) {
         return false;
      }
      cancelOperationGesture(Minecraft.getInstance());
      return true;
   }

   public static void acknowledgeWorkspaceRequest(java.util.UUID transferId) {
      var observed = observedInputState();
      inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(transferId),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, observed));
   }

   public static void abortWorkspaceRequest(java.util.UUID transferId) {
      var observed = observedInputState();
      inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(transferId),
         ClientInputStateMachine.SubmissionEvent.FAILED, observed));
   }

   public static void expireWorkspaceRequest(java.util.UUID transferId) {
      var observed = observedInputState();
      inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(transferId),
         ClientInputStateMachine.SubmissionEvent.EXPIRED, observed));
   }

   @SubscribeEvent
   public static void onKey(Key event) {
      Minecraft minecraft = Minecraft.getInstance();
      long window = minecraft.getWindow().getWindow();
      KeyboardInputSnapshot input = KeyboardInputSnapshot.capture(
         event.getKey(), event.getScanCode(), event.getAction(), event.getModifiers(), System.nanoTime(),
         InputConstants.isKeyDown(window, 342), InputConstants.isKeyDown(window, 346),
         InputConstants.isKeyDown(window, 341), InputConstants.isKeyDown(window, 345)
      );
      if (input.action() == 1 && (input.key() == 257 || input.key() == 335)) {
         input = input.withQuickShapeSubmission(FastPlaceClientPreview.buildingSubmission().orElse(null));
      }
      if (requiresImmediateKeyHandling(minecraft, input)) {
         handleKey(minecraft, input);
      } else {
         inputSession().postKeyboard(input);
      }
   }

   private static boolean requiresImmediateKeyHandling(Minecraft minecraft, KeyboardInputSnapshot input) {
      if (input.key() == 256) return true;
      if (minecraft == null || minecraft.player == null || minecraft.screen != null) return false;
      if (input.key() != minecraft.options.keyDrop.getKey().getValue()) return false;
      ClientInputStateMachine.Dispatch route = inputSession().routing.dispatch(ClientInputStateMachine.InputKind.CANCEL);
      return route == ClientInputStateMachine.Dispatch.CANCEL || route == ClientInputStateMachine.Dispatch.BLOCKED
         || inputSession().canCancelPendingRemotePoint();
   }

   private static void handleKey(Minecraft minecraft, KeyboardInputSnapshot event) {
      synchronizeInputState();
      ClientInputStateMachine.Dispatch inputRoute = inputSession().routing.dispatch(ClientInputStateMachine.InputKind.KEY);
      boolean buildingSession = inputRoute == ClientInputStateMachine.Dispatch.BUILDING;
      boolean geometrySession = inputRoute == ClientInputStateMachine.Dispatch.GEOMETRY;
      boolean operationSession = inputRoute == ClientInputStateMachine.Dispatch.OPERATION;
      boolean altKey = event.altKey();
      boolean ctrlKey = event.controlKey();
      boolean controlDown = event.controlDown();
      if (event.action() == 1 && event.key() == 257
         && (ClientOperationController.reconnectRestorePending()
            || FastPlaceClientPreview.reconnectPreviewRestorePending())
         && minecraft.player != null && minecraft.screen == null) {
         ClientOperationController.confirmReconnectRestore();
         FastPlaceClientPreview.confirmReconnectPreviewRestore();
         return;
      }
      boolean clientReady = minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null;
      boolean operationRestorePending = ClientOperationController.reconnectRestorePending();
      boolean previewRestorePending = FastPlaceClientPreview.reconnectPreviewRestorePending();
      CancelInputSemantics.Decision cancel = CancelInputSemantics.decide(
         event.action(),
         event.key(),
         clientReady,
         clientReady && NetworkRegistry.hasChannel(minecraft.getConnection(), QuitFastPlacePayload.TYPE.id()),
         ClientOperationController.workspaceSubmissionPending(),
         operationRestorePending,
         previewRestorePending
      );
      if (cancel.command() == CancelInputSemantics.Command.REPORT_SUBMISSION_PENDING) {
         ClientInteractionFeedback.show(minecraft, "fastformer.message.operation_submit_pending");
         return;
      }
      if (cancel.command() == CancelInputSemantics.Command.REQUEST_CANCEL) {
         boolean cancellationAccepted = cancelActiveSession(
            minecraft, cancel.dismissOperationRestore(), cancel.dismissPreviewRestore()
         );
         if (CancelInputSemantics.consumesVanillaDrop(
            cancel, cancellationAccepted, cancelKeyCarriesVanillaDrop(minecraft, event.key())
         )) {
            minecraft.options.keyDrop.consumeClick();
         }
         return;
      }
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         return;
      }
      if (minecraft.player != null && minecraft.screen == null && event.action() == 1) {
         if (event.key() == 82 && !controlDown && !event.altDown()
            && inputRoute == ClientInputStateMachine.Dispatch.VANILLA) {
            QuickReplaceMode.toggle(minecraft);
            return;
         }
         if (handleWorkspaceShortcut(minecraft, event.action(), event.key(), controlDown, inputRoute)) {
            return;
         }
      }
      if (altKey
         && (event.action() == 0 || event.action() == 1)
         && minecraft.player != null
         && minecraft.getConnection() != null) {
         handleAltTransition(minecraft, event.altDown(), event.occurredAtNanos());
      }
      if ((altKey || ctrlKey)
         && (event.action() == 0 || event.action() == 1)
         && minecraft.player != null
         && minecraft.getConnection() != null) {
         handleRadialKeyTransition(minecraft, controlDown && event.altDown());
      }
      if (minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && (event.key() == 90 || event.key() == 89)
         && event.action() == 1
         && controlDown) {
         if (operationSession) {
            cancelOperationGesture(minecraft);
         }
         if (event.key() == 90
            && NetworkRegistry.hasChannel(minecraft.getConnection(), WorldUndoPayload.TYPE.id())) {
            PacketDistributor.sendToServer(WorldUndoPayload.INSTANCE, new CustomPacketPayload[0]);
         } else if (event.key() == 89
            && NetworkRegistry.hasChannel(minecraft.getConnection(), WorldRedoPayload.TYPE.id())) {
            PacketDistributor.sendToServer(WorldRedoPayload.INSTANCE, new CustomPacketPayload[0]);
         }
      }
      if (minecraft.player != null && minecraft.screen == null && minecraft.getConnection() != null) {
         SubmissionKeyboardSemantics.Command submissionCommand = SubmissionKeyboardSemantics.fromPhysicalKey(
            event.action(), event.key()
         );
         ClientInputStateMachine.Dispatch submissionRoute = inputSession().routing.dispatch(ClientInputStateMachine.InputKind.SUBMIT);
         boolean needsCandidateContext = SubmissionKeyboardSemantics.requiresCandidateContext(
            submissionCommand, submissionRoute
         );
         boolean canConfirm = needsCandidateContext && ClientPlacementRouter.canConfirm(minecraft);
         SubmissionKeyboardSemantics.Decision submission = SubmissionKeyboardSemantics.decide(
            submissionCommand,
            submissionRoute,
            canConfirm,
            canConfirm && InteractionContext.nearVanillaBlock(minecraft)
         );
         if (submission.accepted()) {
            // A confirmation ends the current input gesture.  If the mouse
            // button release arrives after the server has already consumed
            // this confirmation, it must not become a stale session undo.
            inputSession().undoPress.cancel();
            inputSession().undoPressCaptured = false;
            if (inputSession().modifier.held()) {
               inputSession().modifier.consume();
            }
            if (operationSession) {
               if (ClientOperationController.active()) {
                  if (!ClientOperationController.submitWorkspace(minecraft)) {
                     ClientInteractionFeedback.showWorkspaceSubmitFailure(minecraft);
                  }
               } else if (ClientOperationController.operationAdjustmentStarted()) {
                  if (!ClientPlacementRouter.applyOperation(minecraft, controlDown)) {
                     ClientInteractionFeedback.show(minecraft, "fastformer.message.operation_apply_failed");
                  }
               }
               return;
            }
            boolean submitted = buildingSession
               ? io.github.fastformer.client.quickshape.QuickShapeSubmissionController.begin(
                  minecraft, inputSession().quickShapeSubmission, event.quickShapeSubmission())
               : ClientPlacementRouter.confirm(minecraft);
            if (!submitted) {
                ClientInteractionFeedback.show(minecraft, "fastformer.message.placement_confirm_failed");
            }
         }
      }
   }

   /**
    * Escape reaches the pause screen before the mod receives the key. This hook
    * runs before the screen is installed, so an active session cancels here and
    * the pause screen never opens. An existing screen keeps its own close
    * rules, and a submission still waiting reports instead of cancelling.
    */
   @SubscribeEvent
   public static void onScreenOpening(ScreenEvent.Opening event) {
      Minecraft minecraft = Minecraft.getInstance();
      if (!(event.getNewScreen() instanceof PauseScreen) || event.getCurrentScreen() != null) {
         return;
      }
      // Losing window focus opens the same screen from GameRenderer. Only an
      // Escape press in an active window is a cancel; a lost focus must keep
      // the session and only ends the physical gesture.
      if (!minecraft.isWindowActive()) {
         return;
      }
      if (minecraft.player == null || minecraft.getConnection() == null) {
         return;
      }
      synchronizeInputState();
      CancelInputSemantics.Decision escape = CancelInputSemantics.decideEscape(
         true,
         NetworkRegistry.hasChannel(minecraft.getConnection(), QuitFastPlacePayload.TYPE.id()),
         inputSession().routing.dispatch(ClientInputStateMachine.InputKind.CANCEL)
            == ClientInputStateMachine.Dispatch.CANCEL || inputSession().canCancelPendingRemotePoint(),
         ClientOperationController.workspaceSubmissionPending(),
         ClientOperationController.reconnectRestorePending(),
         FastPlaceClientPreview.reconnectPreviewRestorePending()
      );
      if (escape.command() == CancelInputSemantics.Command.REPORT_SUBMISSION_PENDING) {
         ClientInteractionFeedback.show(minecraft, "fastformer.message.operation_submit_pending");
         return;
      }
      if (escape.command() != CancelInputSemantics.Command.REQUEST_CANCEL) {
         return;
      }
      if (cancelActiveSession(minecraft, escape.dismissOperationRestore(), escape.dismissPreviewRestore())) {
         event.setCanceled(true);
         // pauseGame pauses the sound after the screen call returns, so the
         // matching resume must wait for the next tick.
         inputSession().suppressedPausePausedSound = minecraft.hasSingleplayerServer()
            && minecraft.getSingleplayerServer() != null
            && !minecraft.getSingleplayerServer().isPublished();
      }
   }

   /** Ends the local session for an accepted cancel. False when the phase refused. */
   private static boolean cancelActiveSession(
      Minecraft minecraft, boolean dismissOperationRestore, boolean dismissPreviewRestore
   ) {
      boolean cancellingQuickShape = inputSession().quickShapeSubmission.active()
         || inputSession().routing.ownsQuickShapeSubmission();
      boolean cancelled = inputSession().cancel();
      if (dismissOperationRestore) ClientOperationController.dismissReconnectRestore();
      if (dismissPreviewRestore) FastPlaceClientPreview.dismissReconnectPreviewRestore();
      if (!cancelled) {
         return false;
      }
      cancelOperationGesture(minecraft);
      resetModifierState(minecraft);
      if (!cancellingQuickShape) ClientOperationController.clearWorkspace();
      FastPlaceClientPreview.clearTransientFeedback();
      if (NetworkRegistry.hasChannel(minecraft.getConnection(), QuitFastPlacePayload.TYPE.id())) {
         PacketDistributor.sendToServer(QuitFastPlacePayload.INSTANCE, new CustomPacketPayload[0]);
      }
      return true;
   }

   /**
    * True when the pressed cancel key is also the vanilla drop binding. The
    * vanilla click queue already holds this press, so the cancel must remove
    * exactly that queued click and nothing else.
    */
   private static boolean cancelKeyCarriesVanillaDrop(Minecraft minecraft, int eventKey) {
      if (minecraft.options.keyDrop.isUnbound()) {
         return false;
      }
      return minecraft.options.keyDrop.getKey()
         .equals(InputConstants.Type.KEYSYM.getOrCreate(eventKey));
   }

   private static boolean handleWorkspaceShortcut(
      Minecraft minecraft,
      int action,
      int key,
      boolean controlDown,
      ClientInputStateMachine.Dispatch keyRoute
   ) {
      WorkspaceKeyboardSemantics.Command command = WorkspaceKeyboardSemantics.fromPhysicalKey(action, key, controlDown);
      WorkspaceKeyboardSemantics.Decision decision = WorkspaceKeyboardSemantics.decide(
         command,
         keyRoute,
         inputSession().routing.dispatch(ClientInputStateMachine.InputKind.PASTE_WORKSPACE),
         new WorkspaceKeyboardSemantics.WorkspaceFacts(
            ClientOperationController.active(),
            ClientOperationController.workspaceUndoDepth(),
            ClientOperationController.workspaceEditInProgress(),
            ClientOperationController.workspaceSubmissionPending()
         )
      );
      if (!decision.accepted()) {
         // A missing local step is not a rejection. The world undo request later
         // in onKey owns the press in that case, so it stays unconsumed here.
         if (decision.handsPressToWorldUndo()) {
            return false;
         }
         if (decision.consumesPress()) {
            reportWorkspaceShortcutRejection(minecraft, decision);
         }
         return decision.consumesPress();
      }
      switch (decision.command()) {
         case COPY -> ClientInteractionFeedback.showCopyResult(minecraft, ClientOperationController.copySelected());
         case PASTE -> ClientInteractionFeedback.showPasteResult(minecraft, ClientOperationController.paste(minecraft));
         case SELECT_ALL -> ClientOperationController.selectAllWorkspaceParts();
         case MARK_SELECTED_FOR_DELETION -> ClientOperationController.markSelectedForDeletion();
         case REMOVE_SELECTED -> ClientOperationController.removeSelectedParts();
         case UNDO -> ClientOperationController.undo();
         case NONE -> {
            return false;
         }
      }
      return true;
   }

   /** Reports why a workspace shortcut did not run, without claiming a step. */
   private static void reportWorkspaceShortcutRejection(
      Minecraft minecraft,
      WorkspaceKeyboardSemantics.Decision decision
   ) {
      if (decision.rejection() == WorkspaceKeyboardSemantics.Rejection.SUBMISSION_PENDING) {
         ClientInteractionFeedback.show(minecraft, "fastformer.message.operation_submit_pending");
      }
   }

   private static void handleAltTransition(Minecraft minecraft, boolean down) {
      handleAltTransition(minecraft, down, System.nanoTime());
   }

   private static void handleAltTransition(Minecraft minecraft, boolean down, long occurredAtNanos) {
      if (down == inputSession().modifier.held()) {
         return;
      }
      if (!down) {
         releaseModifierState(minecraft, true, occurredAtNanos);
         return;
      }
      // Alt has no FastFormer meaning while the client is idle. Do not update
      // controller state in that case: a later click must start from a clean
      // interaction state rather than inheriting a modifier that was pressed
      // during ordinary Minecraft play.
      if (minecraft.screen != null || !modifierSubmodeAvailable()) {
         return;
      }
      if (inputSession().routing.dispatch(ClientInputStateMachine.InputKind.KEY) == ClientInputStateMachine.Dispatch.OPERATION) {
         ClientOperationController.setAltMode(true);
      }
      boolean routed = minecraft.screen == null
         && modifierSubmodeAvailable()
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ModifierStatePayload.TYPE.id());
      inputSession().modifier.press(occurredAtNanos, modifierCycleAvailable(), routed);
      if (routed) {
         PacketDistributor.sendToServer(new ModifierStatePayload(true), new CustomPacketPayload[0]);
      }
   }

   /** Releases Alt even if the session ended before the physical key release. */
   private static void releaseModifierState(Minecraft minecraft, boolean allowStageCycle) {
      releaseModifierState(minecraft, allowStageCycle, System.nanoTime());
   }

   private static void releaseModifierState(Minecraft minecraft, boolean allowStageCycle, long occurredAtNanos) {
      ModifierGestureState.Release release = inputSession().modifier.release(occurredAtNanos, MODIFIER_SHORT_PRESS_NANOS);
      ClientOperationController.setAltMode(false);
      if (release.routed() && NetworkRegistry.hasChannel(minecraft.getConnection(), ModifierStatePayload.TYPE.id())) {
         PacketDistributor.sendToServer(new ModifierStatePayload(false), new CustomPacketPayload[0]);
      }
      if (allowStageCycle && release.routed()
         && release.cycleEligible()
         && release.shortPress()
         && NetworkRegistry.hasChannel(minecraft.getConnection(), CycleStageModePayload.TYPE.id())) {
         BlockPos candidate = FastPlaceClientPreview.lineModeCandidate();
         PacketDistributor.sendToServer(
            candidate != null ? new CycleStageModePayload(true, candidate) : CycleStageModePayload.INSTANCE, new CustomPacketPayload[0]
         );
      }
   }

   private static boolean modifierSessionActive() {
      return FastPlaceClientPreview.active()
         || FastPlaceClientPreview.operationActive()
         || FastPlaceClientPreview.geometryActive()
            && (FastPlaceClientPreview.geometryAllows(GeometryAction.MODE_CYCLE)
               || FastPlaceClientPreview.geometryAllows(GeometryAction.SUBMODE));
   }

   private static boolean modifierCycleAvailable() {
      return FastPlaceClientPreview.geometryActive()
         ? FastPlaceClientPreview.geometryAllows(GeometryAction.MODE_CYCLE)
         : modifierSessionActive();
   }

   private static boolean modifierSubmodeAvailable() {
      return FastPlaceClientPreview.geometryActive()
         ? FastPlaceClientPreview.geometryAllows(GeometryAction.SUBMODE)
         : FastPlaceClientPreview.active()
            || FastPlaceClientPreview.operationActive()
            || FastPlaceClientPreview.buildingRaycastSubmodeAvailable();
   }

   private static void handleRadialKeyTransition(Minecraft minecraft, boolean down) {
      if (down == inputSession().radialChordDown) {
         return;
      }
      inputSession().radialChordDown = down;
      if (down && tryOpenGeometryRadial(minecraft)) {
         inputSession().modifier.consume();
      }
   }

   @SubscribeEvent
   public static void onInteraction(InteractionKeyMappingTriggered event) {
      long occurredAtNanos = System.nanoTime();
      Minecraft minecraft = Minecraft.getInstance();
      synchronizeInputState();
      ClientInputStateMachine.Dispatch inputRoute = inputSession().routing.dispatch(ClientInputStateMachine.InputKind.INTERACTION);
      boolean buildingSession = inputRoute == ClientInputStateMachine.Dispatch.BUILDING;
      boolean geometrySession = inputRoute == ClientInputStateMachine.Dispatch.GEOMETRY;
      boolean operationSession = inputRoute == ClientInputStateMachine.Dispatch.OPERATION;
      // A placement or recovery task owns the world operation. Late attack/use
      // events must not reopen a selection or start a second gesture.
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (inputSession().routing.routesToVanilla(ClientInputStateMachine.InputKind.INTERACTION)
         && QuickReplaceMode.canReplace(minecraft) && event.isUseItem()
         && minecraft.screen == null && minecraft.getConnection() != null
         && ClientPlacementRouter.quickReplace(minecraft)) {
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if ((modifierHeld() || physicalAltDown(minecraft, false)) && (event.isAttack() || event.isUseItem())) {
         return;
      }
      if (event.isUseItem()
         && buildingSession
         && !InteractionContext.nearVanillaBlock(minecraft)
         && !inputSession().buildingRightPress.consume()) {
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (event.isAttack()
         && minecraft.player != null
         && minecraft.player.getMainHandItem().isEmpty()
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && !buildingSession
         && !geometrySession
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) {
         OperationInputSemantics.LeftDecision leftDecision = operationLeftDecision(pointerIntent());
         if (leftMousePressAlreadyHandled()) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (leftDecision.yieldToVanilla()) {
            return;
         }
         if (leftDecision.action() == OperationInputSemantics.LeftAction.GIZMO_DRAG) {
            inputSession().operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (leftDecision.action() == OperationInputSemantics.LeftAction.SELECTION_UNDO
            || leftDecision.action() == OperationInputSemantics.LeftAction.ADJUSTMENT_UNDO) {
            if (!inputSession().undoPressCaptured) {
               inputSession().undoPress.press(System.nanoTime());
               inputSession().undoPressCaptured = true;
            }
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (operationSession && modifierHeld()) {
            boolean handled = inputSession().operationClickCapturedButton != 0
               && ClientOperationController.operationSelectionReady()
               && beginOperationGizmoDrag(minecraft, 0);
            if (handled) {
               inputSession().modifier.consume();
            }
            inputSession().operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (ClientOperationController.operationCuboid()) {
            if (!modifierHeld() && !InteractionContext.nearVanillaBlock(minecraft)) {
               OperationPointerTarget target = FastPlaceClientPreview.operationPointerTarget();
               if (target.kind() == OperationPointerKind.WORLD) {
                  queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
                  inputSession().operationClickCapturedButton = 0;
                  event.setSwingHand(false);
                  event.setCanceled(true);
                  return;
               }
               if (target.kind() == OperationPointerKind.FACE) {
                  if (inputSession().operationClickCapturedButton != 0 && beginOperationFaceAdjustment(minecraft, -1, 0)) {
                     inputSession().operationClickCapturedButton = 0;
                  }
                  event.setSwingHand(false);
                  event.setCanceled(true);
                  return;
               }
            }
         }
         if (ClientOperationController.operationPrism()
            && (inputSession().operationClickCapturedButton == 0 || beginOperationPointDrag(minecraft, 0))) {
            inputSession().operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!InteractionContext.nearVanillaBlock(minecraft)
            && ClientOperationController.operationPrism()
            && (inputSession().operationClickCapturedButton == 0 || sendOperationEdgeInsertion(minecraft))) {
            inputSession().operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!InteractionContext.nearVanillaBlock(minecraft)
            && ClientOperationController.operationPrism()
            && FastPlaceClientPreview.operationCandidatePoint() != null
            && (inputSession().operationClickCapturedButton == 0 || sendNextOperationPrismPoint(minecraft))) {
            inputSession().operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!operationSession
            && !InteractionContext.nearVanillaBlock(minecraft)
            && FastPlaceClientPreview.operationCandidatePoint() != null) {
            queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
            inputSession().operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (operationSession && !InteractionContext.nearVanillaBlock(minecraft)) {
            inputSession().operationClickCapturedButton = 0;
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
      }
      if (event.isUseItem()
         && minecraft.player != null
         && minecraft.player.getMainHandItem().isEmpty()
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && inputRoute == ClientInputStateMachine.Dispatch.VANILLA
         && !InteractionContext.nearVanillaBlock(minecraft)
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())
         && FastPlaceClientPreview.operationCandidatePoint() != null) {
         queueRemoteSelectionPoint(OperationPointPayload.Role.SECOND);
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (event.isUseItem()
         && minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && geometrySession) {
         if (InteractionContext.nearVanillaBlock(minecraft)) {
            return;
         }
         if (GeometryInputController.handleGeometryRightClick(minecraft, inputSession(), occurredAtNanos)) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
      }
      if (event.isAttack()
         && minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && (buildingSession || operationSession || geometrySession)) {
         if (InteractionContext.nearVanillaBlock(minecraft)) {
            return;
         }
         if (geometrySession
            && NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id())) {
            if (inputSession().geometryClickCapturedButton == 0) {
               event.setSwingHand(false);
               event.setCanceled(true);
               return;
            }
            if (inputSession().geometryGizmoDrag == null && GeometryInputController.beginGeometryGizmoDrag(minecraft, inputSession(), 0)) {
               event.setSwingHand(false);
               event.setCanceled(true);
               return;
            }
            if (GeometryInputController.beginGeometryInteraction(minecraft, inputSession(), 0)) {
               event.setSwingHand(false);
               event.setCanceled(true);
               return;
            }
         }
         if (!NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())) {
            return;
         }
         if (inputSession().geometryGizmoDrag == null && !inputSession().undoPressCaptured) {
            inputSession().undoPress.press(System.nanoTime());
            inputSession().undoPressCaptured = true;
         }
         event.setSwingHand(false);
         event.setCanceled(true);
      }
   }

   @SubscribeEvent
   public static void onMouseButton(Pre event) {
      long occurredAtNanos = System.nanoTime();
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) return;
      synchronizeInputState();
      if (queueSelectionPointer(minecraft, event.getAction(), event.getButton(), occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      if (queuePointerRelease(event.getAction(), event.getButton(), occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      // During migration, synchronous mouse paths cannot overtake queued physical input.
      if (!drainPhysicalInput(minecraft)) return;
      if (event.getAction() == MouseButtonInputSemantics.PRESS) inputSession().selectionPointer.clear();
      synchronizeInputState();
      ClientInputStateMachine.Dispatch inputRoute = inputSession().routing.dispatch(ClientInputStateMachine.InputKind.POINTER);
      boolean buildingSession = inputRoute == ClientInputStateMachine.Dispatch.BUILDING;
      boolean geometrySession = inputRoute == ClientInputStateMachine.Dispatch.GEOMETRY;
      boolean operationSession = inputRoute == ClientInputStateMachine.Dispatch.OPERATION;
      if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) {
         return;
      }
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         event.setCanceled(true);
         return;
      }
      if (event.getButton() == 1) {
         if (event.getAction() == 1) {
            inputSession().buildingRightPress.press();
         } else if (event.getAction() == 0) {
            inputSession().buildingRightPress.release();
         }
      }
      if (event.getAction() == 1) {
         if (inputSession().routing.accepts(inputSession().clickGestureToken)
            && (inputSession().pointerGesture.kind() != PointerGestureState.Kind.NONE || inputSession().undoPressCaptured)) {
            cancelOperationGesture(minecraft);
         }
         inputSession().clickGestureToken = 0L;
         // A legacy gesture may have blocked capture before its cancellation above.
         if (queueSelectionPointer(minecraft, event.getAction(), event.getButton(), occurredAtNanos)) {
            event.setCanceled(true);
            return;
         }
         inputSession().clickGestureToken = inputSession().routing.beginGesture(event.getButton());
      } else if (event.getAction() == 0) {
         if (!inputSession().routing.finishGesture(event.getButton(), inputSession().clickGestureToken)) {
            if (!inputSession().routing.accepts(inputSession().clickGestureToken)) {
               inputSession().clickGestureToken = 0L;
            }
            return;
         }
         inputSession().clickGestureToken = 0L;
      }
      boolean altMouseChord = physicalAltDown(minecraft, false) || inputSession().modifier.held();
      OperationInteractionIntent pointerIntent = FastPlaceClientPreview.operationInteractionIntent().orElse(null);
      // Alt is an explicit "new selection" chord. It must win over gizmos,
      // existing-part selection and vanilla placement, except for an explicit
      // Gizmo handle. That direct handle hit starts a transform while Alt
      // continues to create a selection everywhere else.
      if (MouseButtonInputSemantics.startsAltWorkspaceGizmoTransform(
         altMouseChord,
         pointerIntent instanceof OperationInteractionIntent.Gizmo,
         event.getAction(),
         event.getButton()
      )) {
         if (handleWorkspacePointerClick(minecraft, pointerIntent, event.getButton(),
            event.getButton() == MouseButtonInputSemantics.RIGHT_BUTTON ? 1 : -1)) {
            inputSession().modifier.consume();
            inputSession().operationClickCapturedButton = event.getButton();
         }
         event.setCanceled(true);
         return;
      }
      if (inputSession().modifier.held() && event.getAction() == 1) {
         inputSession().modifier.consume();
      }
      if (event.getAction() == 1
         && (event.getButton() == 0 || event.getButton() == 1 || event.getButton() == 2)
         && !ClientOperationController.active()
         && inputRoute == ClientInputStateMachine.Dispatch.VANILLA
         && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
         && !InteractionContext.nearVanillaBlock(minecraft)
         && longRangePlacementBlockHit(minecraft) != null) {
         if (event.getButton() == 1) {
            inputSession().buildingRightPress.consume();
         }
         if (ClientPlacementRouter.startPlacement(
            minecraft, BuildingInputSemantics.raycastPlacement(altMouseChord)
         )) {
            event.setCanceled(true);
            return;
         }
      }
      if (modifierHeld()
         && event.getAction() == 1
         && (event.getButton() == 0 || event.getButton() == 1)
         && inputSession().operationDrag == null
         && workspaceGizmoDrag() == null
         && workspaceFaceDrag() == null) {
         if (ClientOperationController.active()
            && adjustWorkspaceSelectionAtCrosshair(minecraft, event.getButton())) {
            inputSession().modifier.consume();
            inputSession().operationClickCapturedButton = event.getButton();
            event.setCanceled(true);
         }
         return;
      }
      if (event.getButton() == 0
         && (buildingSession || geometrySession || operationSession)
         && (NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())
            || NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id()))) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            boolean operationAdjust = operationSession
               && ClientOperationController.operationSelectionReady()
               && operationAdjustModifierHeld();
            boolean operationAlt = operationSession && modifierHeld();
            OperationInputSemantics.LeftDecision leftDecision = operationLeftDecision(pointerIntent);
            MousePressRoutingSemantics.LeftTarget target = leftPressTarget(
               minecraft, event.getAction(), pointerIntent, leftDecision, operationAdjust, operationAlt,
               buildingSession, geometrySession, operationSession
            );
            consumed = switch (target) {
               case NONE -> false;
               case YIELD_TO_VANILLA -> false;
               case WORKSPACE_POINTER -> {
                  handleWorkspacePointerClick(minecraft, pointerIntent, 0, -1);
                  inputSession().operationClickCapturedButton = 0;
                  yield true;
               }
               case WORKSPACE_ADJUST_OR_CAPTURE -> {
                  adjustWorkspaceSelectionAtCrosshair(minecraft, 0);
                  // A confirmed client workspace owns the interaction surface.
                  inputSession().operationClickCapturedButton = 0;
                  yield true;
               }
               case OPERATION_GIZMO -> {
                  beginOperationGizmoDrag(minecraft, 0);
                  inputSession().operationClickCapturedButton = 0;
                  yield true;
               }
               case OPERATION_POINT -> {
                  beginOperationPointDrag(minecraft, 0);
                  inputSession().operationClickCapturedButton = 0;
                  yield true;
               }
               case UNDO -> beginUndoPress();
               case OPERATION_ALT -> {
                  boolean handled = inputSession().operationClickCapturedButton != 0 && beginOperationGizmoDrag(minecraft, 0);
                  if (handled) inputSession().modifier.consume();
                  inputSession().operationClickCapturedButton = 0;
                  yield true;
               }
               case OPERATION_ADJUST -> {
                  boolean handled = beginOperationGizmoDrag(minecraft, 0);
                  if (!handled && ClientOperationController.operationPrism()) {
                     handled = sendOperationPointSelection(minecraft)
                        || beginOperationFaceAdjustment(minecraft, -1, 0);
                  }
                  if (inputSession().operationClickCapturedButton != 0 && handled) inputSession().modifier.consume();
                  inputSession().operationClickCapturedButton = 0;
                  yield true;
               }
               case CUBOID_FIRST_POINT -> {
                  queueRemoteSelectionPoint(OperationPointPayload.Role.FIRST);
                  inputSession().operationClickCapturedButton = 0;
                  yield true;
               }
               case CUBOID_FACE -> {
                  beginOperationFaceAdjustment(minecraft, -1, 0);
                  inputSession().operationClickCapturedButton = 0;
                  yield true;
               }
               case OPERATION_CAPTURE -> {
                  inputSession().operationClickCapturedButton = 0;
                  yield true;
               }
               case GEOMETRY_CAPTURE, GEOMETRY_GIZMO_CAPTURE -> true;
               case GEOMETRY_INTERACTION -> {
                  boolean handled = GeometryInputController.beginGeometryGizmoDrag(minecraft, inputSession(), 0) || GeometryInputController.beginGeometryInteraction(minecraft, inputSession(), 0);
                  if (handled) {
                     inputSession().undoPress.cancel();
                     inputSession().undoPressCaptured = false;
                     yield true;
                  }
                  yield operationSession ? captureOperationPress() : beginFallbackUndoPress(minecraft);
               }
            };
         } else if (event.getAction() == MouseButtonInputSemantics.RELEASE) {
            consumed = finishMouseRelease(minecraft, event.getAction(), event.getButton(), occurredAtNanos);
         }
         if (consumed) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 1 && geometrySession) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            MousePressRoutingSemantics.RightTarget target = rightPressTarget(
               minecraft, event.getAction(), pointerIntent, true, false
            );
            if (target == MousePressRoutingSemantics.RightTarget.YIELD_TO_VANILLA) return;
            consumed = target == MousePressRoutingSemantics.RightTarget.GEOMETRY_CAPTURE
               || target == MousePressRoutingSemantics.RightTarget.GEOMETRY_INTERACTION && GeometryInputController.handleGeometryRightClick(minecraft, inputSession(), occurredAtNanos);
         } else if (event.getAction() == 0
            && inputSession().geometryClickCapturedButton == 1) {
            inputSession().geometryClickCapturedButton = -1;
            consumed = true;
         } else if (event.getAction() == 0
            && inputSession().geometryGizmoDrag != null
            && inputSession().geometryGizmoDrag.mouseButton() == 1) {
            GeometryDragController.finish(minecraft, inputSession());
            consumed = true;
         }
         if (consumed) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 1 && operationSession) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            MousePressRoutingSemantics.RightTarget target = rightPressTarget(
               minecraft, event.getAction(), pointerIntent, false, true
            );
            if (target == MousePressRoutingSemantics.RightTarget.YIELD_TO_VANILLA) return;
            consumed = switch (target) {
               case NONE, YIELD_TO_VANILLA, GEOMETRY_CAPTURE, GEOMETRY_INTERACTION -> false;
               case WORKSPACE_POINTER -> {
                  handleWorkspacePointerClick(minecraft, pointerIntent, 1, 1);
                  inputSession().operationClickCapturedButton = 1;
                  yield true;
               }
               case WORKSPACE_ADJUST_OR_CAPTURE -> {
                  adjustWorkspaceSelectionAtCrosshair(minecraft, 1);
                  inputSession().operationClickCapturedButton = 1;
                  yield true;
               }
               case CONFIRMED_GIZMO_CAPTURE -> {
                  beginOperationGizmoDrag(minecraft, 1);
                  inputSession().operationClickCapturedButton = 1;
                  yield true;
               }
               case OPERATION_ALT -> {
                  if (beginOperationGizmoDrag(minecraft, 1)) inputSession().modifier.consume();
                  inputSession().operationClickCapturedButton = 1;
                  yield true;
               }
               case OPERATION_ADJUST -> {
                  if (beginOperationGizmoDrag(minecraft, 1)
                     || sendOperationPointSelection(minecraft)
                     || beginOperationFaceAdjustment(minecraft, 1, 1)) {
                     inputSession().modifier.consume();
                  }
                  inputSession().operationClickCapturedButton = 1;
                  yield true;
               }
               case OPERATION_POINT -> {
                  beginOperationPointDrag(minecraft, 1);
                  inputSession().operationClickCapturedButton = 1;
                  yield true;
               }
               case OPERATION_EDGE -> {
                  sendOperationEdgeInsertion(minecraft);
                  inputSession().operationClickCapturedButton = 1;
                  yield true;
               }
               case OPERATION_NEXT_POINT -> {
                  sendNextOperationPrismPoint(minecraft);
                  inputSession().operationClickCapturedButton = 1;
                  yield true;
               }
               case CUBOID_SECOND_POINT -> {
                  queueRemoteSelectionPoint(OperationPointPayload.Role.SECOND);
                  inputSession().operationClickCapturedButton = 1;
                  yield true;
               }
               case CUBOID_FACE -> {
                  boolean handled = beginOperationFaceAdjustment(minecraft, 1, 1);
                  inputSession().operationClickCapturedButton = 1;
                  yield handled;
               }
               case CLOSE_PATH -> PathCloseInputDispatcher.press(minecraft, inputSession(), false, occurredAtNanos);
            };
         } else if (event.getAction() == MouseButtonInputSemantics.RELEASE) {
            consumed = finishMouseRelease(minecraft, event.getAction(), event.getButton(), occurredAtNanos);
         }
         if (consumed) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 1 && buildingSession) {
         if (event.getAction() == 1 && !InteractionContext.nearVanillaBlock(minecraft) && PathCloseInputDispatcher.press(minecraft, inputSession(), false, occurredAtNanos)) {
            inputSession().buildingRightPress.consume();
            event.setCanceled(true);
         }
      } else if (buildingSession
         && event.getButton() == MouseButtonInputSemantics.MIDDLE_BUTTON
         && FastPlaceClientPreview.buildingMiddleClickIgnored()) {
         event.setCanceled(true);
      } else if (MouseButtonInputSemantics.requestsBuildingMiddleConfirm(
         buildingSession,
         FastPlaceClientPreview.middleConfirmEnabled(),
         event.getAction(),
         event.getButton()
      )
         && ClientPlacementRouter.quickShape(minecraft)) {
         event.setCanceled(true);
      } else if (event.getButton() == 2
         && event.getAction() == 1
         && operationSession
         && !modifierHeld()
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) {
         BlockPos point = FastPlaceClientPreview.operationCandidatePoint();
         if (point != null) {
            queueRemoteSelectionPoint(OperationPointPayload.Role.EXTRA);
            event.setCanceled(true);
         }
      }
   }

   private static boolean queuePointerRelease(int action, int button, long occurredAtNanos) {
      if (action != MouseButtonInputSemantics.RELEASE) return false;
      var session = inputSession();
      var target = mouseReleaseTarget(action, button);
      if (target == MouseDragReleaseSemantics.Target.NONE
         || !session.routing.accepts(button, session.clickGestureToken)) {
         return false;
      }
      session.postPointerRelease(new PointerReleaseSnapshot(button, occurredAtNanos,
         session.clickGestureToken, session.pointerGestureToken, target));
      return true;
   }

   private static boolean queueSelectionPointer(Minecraft minecraft, int action, int button, long occurredAtNanos) {
      var session = inputSession();
      if (action == MouseButtonInputSemantics.RELEASE) {
         var release = session.selectionPointer.release(button, occurredAtNanos);
         if (release == null) return false;
         session.postSelectionPointer(release);
         return true;
      }
      boolean ownSelectionDrag = session.selectionPointer.owns(ClientOperationController.selectionGestures().capture());
      if (action != MouseButtonInputSemantics.PRESS || pointerGestureInFlight() && !ownSelectionDrag
         || session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) != ClientInputStateMachine.Dispatch.OPERATION) {
         return false;
      }
      var target = FastPlaceClientPreview.operationInteractionIntent().orElse(null);
      boolean alt = physicalAltDown(minecraft, false);
      boolean altGizmo = MouseButtonInputSemantics.startsAltWorkspaceGizmoTransform(
         alt, target instanceof OperationInteractionIntent.Gizmo, action, button);
      if (!altGizmo && queueSelectionDraft(minecraft, target, button, alt, occurredAtNanos)) return true;
      if (!ClientOperationController.active()) return false;
      if (alt && !altGizmo) return false;
      SelectionPressRoute route = altGizmo ? SelectionPressRoute.OBJECT : selectionPressRoute(minecraft, target, button);
      if (route == SelectionPressRoute.NONE) return false;
      if (route == SelectionPressRoute.POINT) {
         queueSelectionPoint(minecraft, button, occurredAtNanos);
         return true;
      }
      var press = SelectionPointerPress.capture(target, button, physicalCtrlDown(minecraft, false),
         button == MouseButtonInputSemantics.RIGHT_BUTTON ? 1 : -1,
         ClientOperationController.interactionScene(), ClientOperationController.workspace(), occurredAtNanos);
      if (press.isEmpty()) return false;
      session.captureSelectionPress(press.orElseThrow(), alt);
      return true;
   }

   private enum SelectionPressRoute { NONE, POINT, OBJECT }

   private static SelectionPressRoute selectionPressRoute(Minecraft minecraft, OperationInteractionIntent target, int button) {
      return switch (button) {
         case MouseButtonInputSemantics.LEFT_BUTTON -> {
            if (!NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())
               && !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id())) {
               yield SelectionPressRoute.NONE;
            }
            yield switch (leftPressTarget(minecraft, MouseButtonInputSemantics.PRESS, target, operationLeftDecision(target),
               false, false, false, false, true)) {
               case WORKSPACE_POINTER -> SelectionPressRoute.OBJECT;
               case WORKSPACE_ADJUST_OR_CAPTURE -> SelectionPressRoute.POINT;
               default -> SelectionPressRoute.NONE;
            };
         }
         case MouseButtonInputSemantics.RIGHT_BUTTON -> switch (
            rightPressTarget(minecraft, MouseButtonInputSemantics.PRESS, target, false, true)) {
            case WORKSPACE_POINTER -> SelectionPressRoute.OBJECT;
            case WORKSPACE_ADJUST_OR_CAPTURE -> SelectionPressRoute.POINT;
            default -> SelectionPressRoute.NONE;
         };
         case MouseButtonInputSemantics.MIDDLE_BUTTON -> SelectionPressRoute.POINT;
         default -> SelectionPressRoute.NONE;
      };
   }

   private static void queueSelectionPoint(Minecraft minecraft, int button, long occurredAtNanos) {
      var workspace = ClientOperationController.workspace();
      int partId = workspace.activeId();
      BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
      inputSession().captureSelectionPress(new SelectionPointPress(ClientOperationController.interactionScene().owner(),
         partId, workspace.interactionId(partId), button, physicalCtrlDown(minecraft, false),
         hit == null ? null : hit.getBlockPos(), occurredAtNanos));
   }

   private static boolean queueSelectionDraft(
      Minecraft minecraft, OperationInteractionIntent target, int button, boolean alt, long occurredAtNanos
   ) {
      var session = inputSession();
      if (button < 0 || button > 2) return false;
      BlockPos point;
      if (MouseButtonInputSemantics.startsAltCreateSelection(alt,
         session.routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION) == ClientInputStateMachine.Dispatch.OPERATION,
         MouseButtonInputSemantics.PRESS, button)) {
         BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
         point = hit == null ? null : hit.getBlockPos();
      } else if (!alt && ClientOperationController.active()
         && (button != MouseButtonInputSemantics.MIDDLE_BUTTON || ClientOperationController.activeSelectionTransformed())
         && target instanceof OperationInteractionIntent.CreateSelection create) {
         point = create.point();
      } else {
         return false;
      }
      session.captureSelectionPress(new SelectionDraftPress(ClientOperationController.interactionScene().owner(),
         ClientOperationController.draftSelectionMode(), button, point, alt, physicalCtrlDown(minecraft, false), occurredAtNanos));
      return true;
   }

   static void queueRemoteSelectionPoint(OperationPointPayload.Role role) {
      inputSession().postRemoteSelectionPoint(RemoteSelectionPointDispatcher.capture(role));
   }

   static void handleRemoteSelectionPoint(RemoteSelectionPointRequest request,
      java.util.function.Consumer<OperationPointPayload> send) {
      RemoteSelectionPointDispatcher.dispatch(request, inputSession().routing, send);
   }

   private static boolean drainPhysicalInput(Minecraft minecraft) {
      var dispatchSession = inputSession();
      var dispatchPlayer = minecraft.player;
      var dispatchConnection = minecraft.getConnection();
      var dispatchLevel = minecraft.level;
      java.util.function.BooleanSupplier contextActive = () -> dispatchSession == inputSession()
         && dispatchPlayer != null && dispatchPlayer == minecraft.player
         && dispatchConnection != null && dispatchConnection == minecraft.getConnection()
         && dispatchLevel != null && dispatchLevel == minecraft.level
         && minecraft.screen == null && minecraft.isWindowActive();
      if (!contextActive.getAsBoolean()) {
         dispatchSession.discardPhysicalEvents();
         return false;
      }
      dispatchSession.drainPhysicalEvents(contextActive,
         key -> handleKey(minecraft, key), scroll -> ScrollInputDispatcher.dispatch(minecraft, dispatchSession, scroll),
         click -> SelectionInputDispatcher.dispatch(minecraft, dispatchSession, click), request -> {
            if (NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) {
               handleRemoteSelectionPoint(request,
                  payload -> PacketDistributor.sendToServer(payload, new CustomPacketPayload[0]));
            }
         }, release -> release.dispatch(dispatchSession,
            mouseReleaseTarget(MouseButtonInputSemantics.RELEASE, release.button()),
            () -> finishMouseRelease(minecraft, MouseButtonInputSemantics.RELEASE,
               release.button(), release.occurredAtNanos())));
      return contextActive.getAsBoolean();
   }

   /** Cancelling a session must also release the server-side modifier state. */
   private static void resetModifierState(Minecraft minecraft) {
      if (inputSession().modifier.held()) {
         releaseModifierState(minecraft, false);
      }
   }

   private static boolean finishMouseRelease(Minecraft minecraft, int action, int button, long releasedAtNanos) {
      return switch (mouseReleaseTarget(action, button)) {
         case OPERATION_POINT_DRAG -> {
            OperationPointDrag finished = finishOperationPointDrag(minecraft);
            finishOperationPointClick(minecraft, finished, releasedAtNanos);
            inputSession().operationClickCapturedButton = -1;
            yield true;
         }
         case OPERATION_DRAG -> {
            finishOperationDrag(minecraft, releasedAtNanos);
            inputSession().operationClickCapturedButton = -1;
            yield true;
         }
         case WORKSPACE_GIZMO_DRAG -> {
            finishWorkspaceGizmoDrag();
            inputSession().operationClickCapturedButton = -1;
            yield true;
         }
         case WORKSPACE_FACE_DRAG -> {
            finishWorkspaceFaceDrag();
            inputSession().operationClickCapturedButton = -1;
            yield true;
         }
         case GEOMETRY_GIZMO_DRAG -> {
            GeometryDragController.finish(minecraft, inputSession());
            if (button == MouseButtonInputSemantics.LEFT_BUTTON) {
               inputSession().undoPress.cancel();
               inputSession().undoPressCaptured = false;
            }
            yield true;
         }
         case OPERATION_CAPTURE -> {
            inputSession().operationClickCapturedButton = -1;
            yield true;
         }
         case GEOMETRY_CAPTURE -> {
            inputSession().geometryClickCapturedButton = -1;
            yield true;
         }
         case UNDO_PRESS -> finishUndoPress(minecraft, releasedAtNanos);
         case NONE -> false;
      };
   }

   private static MouseDragReleaseSemantics.Target mouseReleaseTarget(int action, int button) {
      return MouseDragReleaseSemantics.releaseTarget(action, button, new MouseDragReleaseSemantics.State(
         dragButton(inputSession().operationPointDrag),
         dragButton(inputSession().operationDrag),
         dragButton(workspaceGizmoDrag()),
         dragButton(workspaceFaceDrag()),
         dragButton(inputSession().geometryGizmoDrag),
         inputSession().operationClickCapturedButton,
         inputSession().geometryClickCapturedButton,
         inputSession().undoPressCaptured
      ));
   }

   private static boolean finishUndoPress(Minecraft minecraft, long releasedAtNanos) {
      boolean shortPress = inputSession().undoPress.release(releasedAtNanos, UNDO_SHORT_PRESS_NANOS);
      inputSession().undoPressCaptured = false;
      if (shortPress && NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())) {
         PacketDistributor.sendToServer(UndoFastPlacePayload.INSTANCE, new CustomPacketPayload[0]);
      }
      return true;
   }

   private static int dragButton(OperationPointDrag drag) {
      return drag == null ? -1 : drag.mouseButton();
   }

   private static int dragButton(OperationDrag drag) {
      return drag == null ? -1 : drag.mouseButton();
   }

   private static int dragButton(WorkspaceGizmoDrag drag) {
      return drag == null ? -1 : drag.mouseButton();
   }

   private static int dragButton(WorkspaceFaceDrag drag) {
      return drag == null ? -1 : drag.mouseButton();
   }

   private static int dragButton(GeometryGizmoDrag drag) {
      return drag == null ? -1 : drag.mouseButton();
   }

   private static MousePressRoutingSemantics.LeftTarget leftPressTarget(
      Minecraft minecraft,
      int action,
      OperationInteractionIntent pointerIntent,
      OperationInputSemantics.LeftDecision leftDecision,
      boolean operationAdjust,
      boolean operationAlt,
      boolean buildingSession,
      boolean geometrySession,
      boolean operationSession
   ) {
      OperationPointerTarget pointerTarget = FastPlaceClientPreview.operationPointerTarget();
      boolean operationPointTarget = ClientOperationController.operationPrism()
         && inputSession().operationDrag == null
         && inputSession().operationPointDrag == null
         && FastPlaceClientPreview.operationPointUnderCrosshairIndex() >= 0
         && FastPlaceClientPreview.operationPointCenter(FastPlaceClientPreview.operationPointUnderCrosshairIndex()) != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id());
      boolean operationUndo = leftDecision.action() == OperationInputSemantics.LeftAction.SELECTION_UNDO
         || leftDecision.action() == OperationInputSemantics.LeftAction.ADJUSTMENT_UNDO;
      return MousePressRoutingSemantics.leftTarget(new MousePressRoutingSemantics.LeftState(
         action,
         MouseButtonInputSemantics.LEFT_BUTTON,
         buildingSession || geometrySession || operationSession,
         leftDecision.yieldToVanilla(),
         workspacePointerTarget(pointerIntent),
         ClientOperationController.active(),
         leftDecision.action() == OperationInputSemantics.LeftAction.GIZMO_DRAG,
         operationPointTarget,
         operationUndo,
         operationAlt,
         operationAdjust,
         ClientOperationController.operationCuboid()
            && !InteractionContext.nearVanillaBlock(minecraft)
            && pointerTarget.kind() == OperationPointerKind.WORLD,
         ClientOperationController.operationCuboid()
            && !InteractionContext.nearVanillaBlock(minecraft)
            && pointerTarget.kind() == OperationPointerKind.FACE,
         inputSession().operationClickCapturedButton == MouseButtonInputSemantics.LEFT_BUTTON,
         inputSession().operationDrag != null && inputSession().operationDrag.mouseButton() == MouseButtonInputSemantics.LEFT_BUTTON,
         inputSession().geometryClickCapturedButton == MouseButtonInputSemantics.LEFT_BUTTON,
         inputSession().geometryGizmoDrag != null,
         geometrySession && FastPlaceClientPreview.geometryGizmoHit() != null,
         geometrySession,
         operationSession,
         NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())
      ));
   }

   private static MousePressRoutingSemantics.RightTarget rightPressTarget(
      Minecraft minecraft,
      int action,
      OperationInteractionIntent pointerIntent,
      boolean geometrySession,
      boolean operationSession
   ) {
      OperationPointerTarget pointerTarget = FastPlaceClientPreview.operationPointerTarget();
      boolean nearVanillaBlock = InteractionContext.nearVanillaBlock(minecraft);
      boolean operationAdjust = operationSession
         && ClientOperationController.operationSelectionReady()
         && operationAdjustModifierHeld();
      boolean operationPointTarget = ClientOperationController.operationPrism()
         && inputSession().operationDrag == null
         && inputSession().operationPointDrag == null
         && FastPlaceClientPreview.operationPointUnderCrosshairIndex() >= 0
         && FastPlaceClientPreview.operationPointCenter(FastPlaceClientPreview.operationPointUnderCrosshairIndex()) != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id());
      return MousePressRoutingSemantics.rightTarget(new MousePressRoutingSemantics.RightState(
         action,
         MouseButtonInputSemantics.RIGHT_BUTTON,
         geometrySession,
         operationSession,
         nearVanillaBlock,
         inputSession().geometryClickCapturedButton == MouseButtonInputSemantics.RIGHT_BUTTON,
         workspacePointerTarget(pointerIntent),
         ClientOperationController.active(),
         ClientOperationController.operationSelectionConfirmed(),
         operationAdjust,
         operationSession && modifierHeld(),
         operationPointTarget,
         !nearVanillaBlock
            && ClientOperationController.operationPrism()
            && FastPlaceClientPreview.operationPrismEdgeInsertion() != null
            && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationInsertPointPayload.TYPE.id()),
         !nearVanillaBlock
            && ClientOperationController.operationPrism()
            && FastPlaceClientPreview.operationCandidatePoint() != null
            && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id()),
         ClientOperationController.operationCuboid() && pointerTarget.kind() == OperationPointerKind.WORLD,
         ClientOperationController.operationCuboid() && pointerTarget.kind() == OperationPointerKind.FACE,
         FastPlaceClientPreview.operationPrismBaseOpen()
      ));
   }

   private static boolean workspacePointerTarget(OperationInteractionIntent pointerIntent) {
      return pointerIntent instanceof OperationInteractionIntent.Gizmo
         || pointerIntent instanceof OperationInteractionIntent.Face
         || pointerIntent instanceof OperationInteractionIntent.Part part && part.partId() > 0;
   }

   private static boolean beginUndoPress() {
      if (!inputSession().undoPressCaptured) {
         inputSession().undoPress.press(System.nanoTime());
         inputSession().undoPressCaptured = true;
      }
      return true;
   }

   private static boolean captureOperationPress() {
      inputSession().operationClickCapturedButton = MouseButtonInputSemantics.LEFT_BUTTON;
      return true;
   }

   private static boolean beginFallbackUndoPress(Minecraft minecraft) {
      return NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id()) && beginUndoPress();
   }

   @SubscribeEvent
   public static void onClientTick(Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      resumeSoundAfterSuppressedPause(minecraft);
      ClientSessionManager.instance().observePlayer(minecraft);
      InteractionContext.tick(minecraft);
      if (minecraft.player == null || minecraft.getConnection() == null) {
         io.github.fastformer.network.client.ClientPayloadDispatcher.endWorldSession();
         QuickReplaceMode.clear();
         cancelWorkspaceEditIfPresent();
         ClientOperationController.selectionGestures().clear();
         if (inputSession().operationSessionWasActive) {
            minecraft.options.keyAttack.setDown(false);
         }
         inputSession().reset();
         ClientOperationController.setAltMode(false);
         InteractionContext.reset();
         FastPlaceClientPreview.clearTransientFeedback();
        return;
      }
      io.github.fastformer.network.client.ClientPayloadDispatcher.onClientTick();
      inputSession().drainSubmissionEvents();
      drainPhysicalInput(minecraft);
      io.github.fastformer.client.quickshape.QuickShapeSubmissionController.tick(minecraft, inputSession().quickShapeSubmission);
      ClientOperationController.onClientTick();
      ClientOperationController.sourceMask().reapply();
      FastPlaceClientPreview.onClientTick();
      synchronizeInputState();
      if (activeSession() && !InputContextBoundary.pointerContextIntact(
         pointerGestureInFlight(), minecraft.screen != null, minecraft.isWindowActive()
      )) {
         suspendPointerGesture(minecraft);
      }
      if (inputSession().routing.dispatch(ClientInputStateMachine.InputKind.KEY)
         != ClientInputStateMachine.Dispatch.BLOCKED) {
         pollModifierKeys(minecraft);
      }
      if (inputSession().routing.routesToVanilla(ClientInputStateMachine.InputKind.INTERACTION)
         && QuickReplaceMode.active() && minecraft.screen == null && minecraft.options.keyUse.isDown()) {
         ClientPlacementRouter.quickReplace(minecraft);
      }
      if (!minecraft.options.keyUse.isDown()) {
         inputSession().buildingRightPress.release();
      }
      boolean operationActive = FastPlaceClientPreview.operationActive();
      if (inputSession().operationSessionWasActive && !operationActive) {
         minecraft.options.keyAttack.setDown(false);
      }
      inputSession().operationSessionWasActive = operationActive;
      if (!activeSession()) {
         cancelWorkspaceEditIfPresent();
         inputSession().operationDrag = null;
         inputSession().operationPointDrag = null;
         resetOperationPointClicks();
         inputSession().undoPress.cancel();
         inputSession().undoPressCaptured = false;
         inputSession().geometryClickCapturedButton = -1;
         inputSession().operationClickCapturedButton = -1;
         ClientOperationController.selectionGestures().clear();
         cancelPointerGesture();
      }
      if (inputSession().operationDrag == null && inputSession().operationPointDrag == null
         && inputSession().geometryGizmoDrag == null && workspaceGizmoDrag() == null && workspaceFaceDrag() == null) {
         return;
      }

      DragAdvanceSemantics.Plan dragPlan = DragAdvanceSemantics.plan(new DragAdvanceSemantics.State(
         inputSession().operationDrag != null,
         ownsPointerGesture(PointerGestureState.Kind.OPERATION_FACE)
            || ownsPointerGesture(PointerGestureState.Kind.OPERATION_GIZMO),
         inputSession().operationPointDrag != null,
         ownsPointerGesture(PointerGestureState.Kind.OPERATION_POINT),
         inputSession().geometryGizmoDrag != null,
         workspaceGizmoDrag() != null,
         workspaceFaceDrag() != null,
         FastPlaceClientPreview.operationActive()
      ));
      if (dragPlan.clearOperationDrag()) inputSession().operationDrag = null;
      if (dragPlan.clearOperationPointDrag()) inputSession().operationPointDrag = null;
      if (dragPlan.exclusiveOwner() == DragAdvanceSemantics.Owner.WORKSPACE_FACE) {
         SelectionGestureController.updateFace(inputSession(), minecraft);
         return;
      }
      if (dragPlan.exclusiveOwner() == DragAdvanceSemantics.Owner.WORKSPACE_GIZMO) {
         SelectionGestureController.updateGizmo(inputSession(), minecraft, physicalCtrlDown(minecraft, false));
         return;
      }
      if (dragPlan.advanceGeometryGizmo()) GeometryDragController.update(minecraft, inputSession(), physicalCtrlDown(minecraft, false));
      if (dragPlan.advanceOperationPoint()) updateOperationPointDrag(minecraft);
      if (dragPlan.clearInactiveOperationDrags()) {
         inputSession().operationDrag = null;
         inputSession().operationPointDrag = null;
         return;
      }
      if (!dragPlan.advanceOperationDrag()) return;
      long now = System.nanoTime();
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      Vec3 axisPoint = OperationGeometry.closestPointOnAxisToRay(
         inputSession().operationDrag.frame().origin(), inputSession().operationDrag.normal(), eye, view
      );
      int projectedSteps = inputSession().operationDrag.frame().project(axisPoint, inputSession().operationDrag.normal());
      if (inputSession().operationDrag.faceHit() != null
         && inputSession().operationDrag.deferredClick().awaitingRelease(now, OPERATION_FACE_SHORT_PRESS_NANOS)) {
         return;
      }
      if (inputSession().operationDrag.deferredClick().steps() != 0) {
         inputSession().operationDrag = inputSession().operationDrag.withDeferredClick(inputSession().operationDrag.deferredClick().cancel());
      }
      if (inputSession().operationDrag.faceHit() != null && ClientOperationController.operationCuboid()) {
         OperationSelectionVolume selection = FastPlaceClientPreview.operationSelection();
         boolean inside = selection != null
            && selection.bounds().inflate(OperationSelectionVolume.RAYCAST_INFLATE).contains(eye);
         if (inside != inputSession().operationDrag.frame().reversed()) {
            inputSession().operationDrag = inputSession().operationDrag.withFrame(
               inputSession().operationDrag.frame().rebase(axisPoint, projectedSteps, inside)
            );
         }
      }
      int totalSteps = inputSession().operationDrag.frame().project(axisPoint, inputSession().operationDrag.normal());
      int delta = totalSteps - inputSession().operationDrag.sentSteps();
      if (delta != 0 && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationExtendPayload.TYPE.id())) {
         int clippedDelta = ClientInputMath.clampDragSteps(delta, MAX_DRAG_STEPS_PER_PACKET);
         PacketDistributor.sendToServer(
            new OperationExtendPayload(inputSession().operationDrag.axis(), inputSession().operationDrag.positive(), clippedDelta, false),
            new CustomPacketPayload[0]
         );
         inputSession().operationDrag = inputSession().operationDrag.withSentSteps(inputSession().operationDrag.sentSteps() + clippedDelta);
         if (inputSession().operationDrag.gizmoKey() != null) {
            FastPlaceClientPreview.noteGizmoFeedback(
               inputSession().operationDrag.gizmoKey().axis(),
               AxisGizmo.Operation.MOVE,
               inputSession().operationDrag.sentSteps(),
               inputSession().operationDrag.gizmoBaseValue()
            );
         }
      }
   }

   @SubscribeEvent
   public static void onVisualPointerFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
      Minecraft minecraft = Minecraft.getInstance();
      OperationInteractionIntent intent = null;
      if (minecraft.player != null && minecraft.level != null && minecraft.screen == null
         && minecraft.isWindowActive() && ClientOperationController.active()) {
         intent = FastPlaceClientPreview.operationInteractionIntent().orElse(null);
      }
      ClientOperationController.updateVisualHover(intent);
   }

   private static void updateOperationPointDrag(Minecraft minecraft) {
      OperationPointDrag drag = inputSession().operationPointDrag;
      if (drag == null || !FastPlaceClientPreview.operationActive() || ClientOperationController.operationSelectionConfirmed()) {
         inputSession().operationPointDrag = null;
         return;
      }
      if (!ownsPointerGesture(PointerGestureState.Kind.OPERATION_POINT)) {
         inputSession().operationPointDrag = null;
         return;
      }
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      OperationPointDragConstraint constraint = OperationPointDragCalculator.selectConstraint(drag, eye, view);
      if (constraint != drag.constraint()) {
         drag = OperationPointDragCalculator.rebase(drag, constraint, eye, view);
         inputSession().operationPointDrag = drag;
      }
      BlockPos desired = constraint == OperationPointDragConstraint.LINE
         ? OperationPointDragCalculator.lineTarget(drag, eye, view)
         : OperationPointDragCalculator.planeTarget(drag, eye, view);
      if (desired == null) {
         return;
      }
      BlockPos target = OperationPointDragCalculator.nextTarget(drag.sentTarget(), desired, drag, constraint);
      if (target.equals(drag.sentTarget())
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         inputSession().operationPointDrag = drag.withConstraint(constraint);
         return;
      }
      PacketDistributor.sendToServer(
         new OperationPointDragPayload(drag.pointIndex(), target, constraint, false), new CustomPacketPayload[0]
      );
      inputSession().operationPointDrag = drag.withSentTarget(target).withConstraint(constraint);
   }

   @SubscribeEvent
   public static void onMouseScroll(MouseScrollingEvent event) {
      synchronizeInputState();
      ClientInputStateMachine.Dispatch inputRoute = inputSession().routing.dispatch(ClientInputStateMachine.InputKind.SCROLL);
      Minecraft minecraft = Minecraft.getInstance();
      boolean sessionReady = minecraft.player != null && minecraft.screen == null;
      boolean candidateChannelAvailable = minecraft.getConnection() != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ScrollCandidatePayload.TYPE.id());
      ScrollInputSemantics.Decision scroll = ScrollInputSemantics.decide(
         event.getScrollDeltaY(),
         inputRoute,
         sessionReady && ClientOperationController.active(),
         sessionReady && candidateChannelAvailable && FastPlaceClientPreview.usesScrollContext(),
         modifierInputRoute(ModifierInput.SCROLL) == ModifierInputRoute.VANILLA,
         sessionReady && InteractionContext.nearVanillaBlock(minecraft)
      );
      switch (scroll.command()) {
         case BLOCK -> event.setCanceled(true);
         case WORKSPACE_MOVE -> {
            BlockPos offset = OperationGeometry.viewAxisStep(minecraft.player.getViewVector(1.0F), scroll.direction());
            var move = SelectionScrollMove.capture(ClientOperationController.interactionScene().owner(),
               ClientOperationController.workspace(), offset);
            if (move.isPresent() && !ClientOperationController.workspaceSubmissionPending()) {
               inputSession().postScroll(new ScrollInputSnapshot(scroll.direction(), move.orElseThrow()));
               event.setCanceled(true);
            }
         }
         case CANDIDATE_SCROLL -> {
            inputSession().postScroll(new ScrollInputSnapshot(scroll.direction()));
            if (inputSession().modifier.held()) {
               inputSession().modifier.consume();
            }
            event.setCanceled(true);
         }
         case YIELD_TO_VANILLA -> {
            inputSession().modifier.consume();
         }
         case NONE -> {
         }
      }
   }

   private static ModifierInputRoute modifierInputRoute(ModifierInput input) {
      if (ClientOperationController.operationSelectionConfirmed()) {
         return ModifierInputRoute.SESSION;
      }
      return inputSession().modifier.held() && input == ModifierInput.SCROLL
         ? ModifierInputRoute.VANILLA
         : ModifierInputRoute.SESSION;
   }

   private static boolean beginOperationDrag(Minecraft minecraft, int mouseButton, int shortPressSteps) {
      OperationSelectionVolume selection = FastPlaceClientPreview.operationSelection();
      if (selection == null
         || shortPressSteps == 0
         || !ClientOperationController.operationSelectionReady()
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationExtendPayload.TYPE.id())) {
         return false;
      }

      Vec3 eye = minecraft.player.getEyePosition();
      OperationGeometry.RayHit hit = ClientOperationController.operationCuboid()
         ? FastPlaceClientPreview.operationFaceHit()
         : selection.raycast(eye, minecraft.player.getViewVector(1.0F), OPERATION_REACH);
      if (hit == null) {
         return false;
      }

      Vec3 normal = hit.normal();
      int axis = hit.axis();
      boolean positive = normal.dot(selection.axis(axis)) > 0.0;
      if (!ClientOperationController.operationCuboid() && (axis != 2 || !positive)) {
         return false;
      }
      boolean reverseInside = ClientOperationController.operationCuboid() && selection.contains(eye);
      int deferredSteps = reverseInside ? -shortPressSteps : shortPressSteps;
      inputSession().operationDrag = new OperationDrag(
         axis,
         positive,
         DragAxisFrame.start(hit.point(), reverseInside),
         normal,
         0,
         mouseButton,
         hit,
         null,
         0.0,
         DeferredDragClick.start(System.nanoTime(), deferredSteps)
      );
      inputSession().pointerGestureToken = inputSession().pointerGesture.begin(PointerGestureState.Kind.OPERATION_FACE);
      return true;
   }

   private static boolean beginOperationGizmoDrag(Minecraft minecraft, int mouseButton) {
      OperationInteractionIntent.Gizmo workspaceTarget = FastPlaceClientPreview.operationWorkspaceGizmoHit();
      if (workspaceTarget != null) {
         var press = SelectionPointerPress.capture(workspaceTarget, mouseButton, physicalCtrlDown(minecraft, false), 0,
            ClientOperationController.interactionScene(), ClientOperationController.workspace());
         return press.isPresent() && beginWorkspaceGizmoDrag(workspaceTarget, press.get().button(), press.get().control());
      }
      AxisGizmo gizmo = FastPlaceClientPreview.operationGizmo();
      AxisGizmo.Hit hit = FastPlaceClientPreview.operationGizmoHit();
      if (gizmo == null || hit == null) {
         return false;
      }
      AxisGizmo.Handle handle = hit.handle();
      if (ClientOperationController.operationSelectionReady()) {
         return GeometryDragController.beginConfirmedOperation(minecraft, inputSession(), gizmo, hit, mouseButton);
      }
      int axis = ClientInputMath.geometryAxisIndex(handle.axis());
      boolean positive = handle.direction() != AxisGizmo.Direction.NEGATIVE;
      Vec3 vector = gizmo.axisVector(handle.axis()).scale(positive ? 1.0 : -1.0);
      int encodedAxis = handle.operation() == AxisGizmo.Operation.MOVE
         ? axis + (FastPlaceClientPreview.operationPointSelected() ? 6 : 3)
         : axis;
      inputSession().operationDrag = new OperationDrag(
         encodedAxis,
         positive,
         DragAxisFrame.start(hit.point(), !positive),
         vector,
         0,
         mouseButton,
         null,
         handle.key(),
         ClientInputMath.axisComponent(gizmo.center(), handle.axis()),
         DeferredDragClick.none()
      );
      inputSession().pointerGestureToken = inputSession().pointerGesture.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      return true;
   }

   /** Handles the shared workspace hit targets for either mouse button. */
   private static boolean handleWorkspacePointerClick(
      Minecraft minecraft, OperationInteractionIntent pointerIntent, int mouseButton, int shortPressSteps
   ) {
      var press = SelectionPointerPress.capture(
         pointerIntent, mouseButton, physicalCtrlDown(minecraft, false), shortPressSteps,
         ClientOperationController.interactionScene(), ClientOperationController.workspace()
      );
      if (press.isEmpty()) return false;
      SelectionGestureController.press(inputSession(), minecraft, press.orElseThrow());
      return true;
   }

   private static boolean beginWorkspaceGizmoDrag(
      OperationInteractionIntent.Gizmo target, int mouseButton, boolean control
   ) {
      return SelectionGestureController.beginWorkspaceGizmoDrag(inputSession(), target, mouseButton, control);
   }

   private static boolean beginOperationPointDrag(Minecraft minecraft, int mouseButton) {
      if (!ClientOperationController.operationPrism()
         || inputSession().operationDrag != null
         || inputSession().operationPointDrag != null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         return false;
      }
      int pointIndex = FastPlaceClientPreview.operationPointUnderCrosshairIndex();
      Vec3 center = FastPlaceClientPreview.operationPointCenter(pointIndex);
      if (pointIndex < 0 || center == null) {
         return false;
      }
      BlockPos initialPoint = BlockPos.containing(center);
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      Vec3 axisBaselines = new Vec3(
         OperationPointDragCalculator.axisOffset(center, eye, view, 0),
         OperationPointDragCalculator.axisOffset(center, eye, view, 1),
         OperationPointDragCalculator.axisOffset(center, eye, view, 2)
      );
      SelectionPrism.GridPlane plane = FastPlaceClientPreview.operationPointGridPlane(pointIndex);
      SelectionPrism.GridLine line = FastPlaceClientPreview.operationPointGridLine(pointIndex);
      Vec3 planeHit = plane == null ? null : plane.rayIntersection(eye, view);
      Vec3 planeGrabOffset = planeHit == null ? Vec3.ZERO : center.subtract(planeHit);
      double lineGrabBaseline = line == null ? 0.0 : line.rayOffset(eye, view) - line.offset(initialPoint);
      inputSession().operationPointDrag = new OperationPointDrag(
         pointIndex,
         mouseButton,
         initialPoint,
         initialPoint,
         plane,
         planeGrabOffset,
         line,
         lineGrabBaseline,
         axisBaselines,
         line != null && plane == null
            ? OperationPointDragConstraint.LINE
            : plane != null ? OperationPointDragConstraint.PLANE : OperationPointDragConstraint.FREE,
         System.nanoTime()
      );
      inputSession().pointerGestureToken = inputSession().pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      PacketDistributor.sendToServer(
         new OperationPointDragPayload(pointIndex, initialPoint, inputSession().operationPointDrag.constraint(), false),
         new CustomPacketPayload[0]
      );
      return true;
   }

   private static boolean sendOperationEdgeInsertion(Minecraft minecraft) {
      if (FastPlaceClientPreview.operationPrismEdgeInsertion() == null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationInsertPointPayload.TYPE.id())) {
         return false;
      }
      PacketDistributor.sendToServer(OperationInsertPointPayload.INSTANCE, new CustomPacketPayload[0]);
      return true;
   }

   private static boolean sendNextOperationPrismPoint(Minecraft minecraft) {
      if (!ClientOperationController.operationPrism()
         || FastPlaceClientPreview.operationCandidatePoint() == null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) {
         return false;
      }
      OperationPointPayload.Role role = FastPlaceClientPreview.operationNeedsFirst()
         ? OperationPointPayload.Role.FIRST
         : FastPlaceClientPreview.operationNeedsSecond()
         ? OperationPointPayload.Role.SECOND
         : OperationPointPayload.Role.EXTRA;
      queueRemoteSelectionPoint(role);
      return true;
   }

   private static boolean sendOperationPointSelection(Minecraft minecraft) {
      if (!ClientOperationController.operationPrism()) {
         return false;
      }
      int index = FastPlaceClientPreview.operationPointUnderCrosshairIndex();
      if (index < 0 || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationSelectPointPayload.TYPE.id())) {
         return false;
      }
      PacketDistributor.sendToServer(new OperationSelectPointPayload(index), new CustomPacketPayload[0]);
      return true;
   }

   private static boolean sendOperationPointRemoval(Minecraft minecraft, int index) {
      if (index < 0 || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationRemovePointPayload.TYPE.id())) {
         return false;
      }
      PacketDistributor.sendToServer(new OperationRemovePointPayload(index), new CustomPacketPayload[0]);
      return true;
   }

   private static boolean beginOperationFaceAdjustment(Minecraft minecraft, int steps, int mouseButton) {
      return beginOperationDrag(minecraft, mouseButton, steps);
   }

   private static void pollModifierKeys(Minecraft minecraft) {
      if (minecraft.player == null || minecraft.getConnection() == null) {
         inputSession().modifier.reset();
         ClientOperationController.setAltMode(false);
         inputSession().radialChordDown = false;
         return;
      }
      handleAltTransition(minecraft, physicalAltDown(minecraft, false));
      handleRadialKeyTransition(minecraft, physicalCtrlDown(minecraft, false) && physicalAltDown(minecraft, false));
   }

   private static boolean physicalAltDown(Minecraft minecraft, boolean eventPressed) {
      long window = minecraft.getWindow().getWindow();
      return eventPressed || InputConstants.isKeyDown(window, 342) || InputConstants.isKeyDown(window, 346);
   }

   private static boolean physicalCtrlDown(Minecraft minecraft, boolean eventPressed) {
      long window = minecraft.getWindow().getWindow();
      return eventPressed || InputConstants.isKeyDown(window, 341) || InputConstants.isKeyDown(window, 345);
   }

   private static boolean tryOpenGeometryRadial(Minecraft minecraft) {
      if (minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && !FastPlaceClientPreview.active()
         && !FastPlaceClientPreview.operationActive()
         && (!FastPlaceClientPreview.geometryActive() || FastPlaceClientPreview.geometryAwaitingFirstPoint())) {
         minecraft.setScreen(new GeometryRadialScreen());
         return true;
      }
      return false;
   }

   private static void resetOperationPointClicks() {
      inputSession().lastOperationPointLeftClickAt = 0L;
      inputSession().lastOperationPointLeftClickIndex = -1;
      inputSession().lastOperationPointRightClickAt = 0L;
      inputSession().lastOperationPointRightClickIndex = -1;
   }

   public static boolean precisionHudActive() {
      return modifierReticleMode() != ModifierReticleMode.NONE;
   }

   public static boolean modifierHeld() {
      return inputSession().modifier.held();
   }

   public static boolean controlHeld() {
      Minecraft minecraft = Minecraft.getInstance();
      return minecraft.player != null && physicalCtrlDown(minecraft, false);
   }

   public static boolean operationAdjustModifierHeld() {
      return inputSession().modifier.held();
   }

   private enum ModifierInput {
      SCROLL
   }

   private enum ModifierInputRoute {
      SESSION,
      VANILLA
   }

   public static ModifierReticleMode modifierReticleMode() {
      if (!inputSession().modifier.held() || inputSession().radialChordDown || !modifierSubmodeAvailable()) {
         return ModifierReticleMode.NONE;
      }
      if (FastPlaceClientPreview.embeddedModifierReticle()) {
         return ModifierReticleMode.EMBEDDED;
      }
      if (FastPlaceClientPreview.halfGridModifierReticle()) {
         return ModifierReticleMode.HALF_GRID;
      }
      return ModifierReticleMode.NONE;
   }

   public static OperationGeometry.RayHit operationFaceDragHit() {
      return inputSession().operationDrag == null ? null : inputSession().operationDrag.faceHit();
   }

   public static OperationGeometry.RayHit workspaceFaceDragHit() {
      return workspaceFaceDrag() == null ? null : workspaceFaceDrag().hit();
   }

   public static int workspaceFaceDragPartId() {
      return workspaceFaceDrag() == null ? 0 : workspaceFaceDrag().baseline().id();
   }

   public static AxisGizmo.Axis operationGizmoDragAxis() {
      if (ClientOperationController.operationSelectionReady() && inputSession().geometryGizmoDrag != null) {
         return inputSession().geometryGizmoDrag.axis();
      }
      return inputSession().operationDrag == null || inputSession().operationDrag.gizmoKey() == null
         ? null
         : inputSession().operationDrag.gizmoKey().axis();
   }

   public static AxisGizmo.Operation operationGizmoDragOperation() {
      if (ClientOperationController.operationSelectionReady() && inputSession().geometryGizmoDrag != null) {
         return inputSession().geometryGizmoDrag.operation();
      }
      return inputSession().operationDrag == null || inputSession().operationDrag.gizmoKey() == null
         ? null
         : inputSession().operationDrag.gizmoKey().operation();
   }

   public static AxisGizmo.HandleKey operationGizmoDragKey() {
      if (workspaceGizmoDrag() != null) {
         return new AxisGizmo.HandleKey(
            workspaceGizmoDrag().operation(), workspaceGizmoDrag().axis(), workspaceGizmoDrag().direction()
         );
      }
      if (ClientOperationController.operationSelectionReady() && inputSession().geometryGizmoDrag != null) {
         return new AxisGizmo.HandleKey(
            inputSession().geometryGizmoDrag.operation(), inputSession().geometryGizmoDrag.axis(), inputSession().geometryGizmoDrag.direction()
         );
      }
      return inputSession().operationDrag == null ? null : inputSession().operationDrag.gizmoKey();
   }

   public static boolean workspaceGizmoDragMatches(int partId, boolean common) {
      return workspaceGizmoDrag() != null
         && workspaceGizmoDrag().common() == common
         && (common || workspaceGizmoDrag().partId() == partId);
   }

   public static int operationGizmoDragSteps() {
      if (workspaceGizmoDrag() != null) {
         return workspaceGizmoDrag().sentSteps();
      }
      if (ClientOperationController.operationSelectionReady() && inputSession().geometryGizmoDrag != null) {
         return inputSession().geometryGizmoDrag.sentSteps();
      }
      return inputSession().operationDrag == null || inputSession().operationDrag.gizmoKey() == null ? 0 : inputSession().operationDrag.sentSteps();
   }

   public static double operationGizmoDragBaseValue() {
      if (ClientOperationController.operationSelectionReady() && inputSession().geometryGizmoDrag != null) {
         return inputSession().geometryGizmoDrag.baseValue();
      }
      return inputSession().operationDrag == null || inputSession().operationDrag.gizmoKey() == null ? 0.0 : inputSession().operationDrag.gizmoBaseValue();
   }

   public static SelectionPrism.GridPlane operationPointDragPlane() {
      if (inputSession().operationPointDrag == null || inputSession().operationPointDrag.plane() == null) {
         return null;
      }
      return inputSession().operationPointDrag.plane();
   }

   public static SelectionPrism.GridLine operationPointDragLine() {
      if (inputSession().operationPointDrag == null || inputSession().operationPointDrag.line() == null) {
         return null;
      }
      return inputSession().operationPointDrag.line();
   }

   public static BlockPos operationPointDragTarget() {
      return inputSession().operationPointDrag == null ? null : inputSession().operationPointDrag.sentTarget();
   }

   public static AxisGizmo.Axis geometryGizmoDragAxis() {
      return inputSession().geometryGizmoDrag == null ? null : inputSession().geometryGizmoDrag.axis();
   }

   public static AxisGizmo.Operation geometryGizmoDragOperation() {
      return inputSession().geometryGizmoDrag == null ? null : inputSession().geometryGizmoDrag.operation();
   }

   public static AxisGizmo.HandleKey geometryGizmoDragKey() {
      return inputSession().geometryGizmoDrag == null
         ? null
         : new AxisGizmo.HandleKey(
            inputSession().geometryGizmoDrag.operation(),
            inputSession().geometryGizmoDrag.axis(),
            inputSession().geometryGizmoDrag.direction()
         );
   }

   public static int geometryGizmoDragSteps() {
      return inputSession().geometryGizmoDrag == null ? 0 : inputSession().geometryGizmoDrag.sentSteps();
   }

   public static double geometryGizmoDragBaseValue() {
      return inputSession().geometryGizmoDrag == null ? 0.0 : inputSession().geometryGizmoDrag.baseValue();
   }

   private static double operationRotationRadians(Minecraft minecraft, int rawSteps) {
      return RotationInputAngles.resolve(rawSteps, physicalCtrlDown(minecraft, false), inputSession().modifier);
   }

   static void finishWorkspaceGizmoDrag() {
      SelectionGestureController.finishGizmo(inputSession());
   }

   static void finishWorkspaceFaceDrag() {
      SelectionGestureController.finishFace(inputSession());
   }

   private static boolean ownsSelectionCapture(SelectionDragCapture capture) {
      return capture.matches(
         ClientOperationController.interactionScene().owner(), ClientOperationController.workspace(), inputSession().pointerGestureToken
      );
   }

   private static WorkspaceGizmoDrag workspaceGizmoDrag() {
      return ClientOperationController.selectionGestures().gizmo();
   }

   private static WorkspaceFaceDrag workspaceFaceDrag() {
      return ClientOperationController.selectionGestures().face();
   }

   private static boolean activeSession() {
      return FastPlaceClientPreview.active()
         || FastPlaceClientPreview.operationActive()
         || FastPlaceClientPreview.geometryActive();
   }

   /** Refreshes the routing phase before every event, including same-tick task transitions. */
   private static void synchronizeInputState() {
      var observed = observedInputState();
      var routing = inputSession().routing;
      long generation = routing.generation();
      routing.observe(observed);
      if (routing.generation() != generation) {
         cancelOperationGesture(Minecraft.getInstance());
      }
   }

   private static ClientInputStateMachine.State observedInputState() {
      return ObservedInputState.stateFor(observedSessions());
   }

   /** Collects the live session flags. The order of the state lives in ObservedInputState. */
   private static ObservedInputState.Sessions observedSessions() {
      return new ObservedInputState.Sessions(
         FastPlaceClientPreview.activity() == io.github.fastformer.fastplace.FastPlaceActivity.RESTORE_TASK,
         FastPlaceClientPreview.taskActive(),
         FastPlaceClientPreview.operationActive(),
         ClientOperationController.active(),
         ClientOperationController.operationSelectionConfirmed(),
         FastPlaceClientPreview.geometryActive(),
         FastPlaceClientPreview.active()
      );
   }

   private static OperationInteractionIntent pointerIntent() {
      return FastPlaceClientPreview.operationInteractionIntent().orElse(null);
   }

   private static OperationInputSemantics.LeftDecision operationLeftDecision(
      OperationInteractionIntent pointerIntent
   ) {
      boolean operationActive = FastPlaceClientPreview.operationActive();
      boolean operationAdjust = operationActive
         && ClientOperationController.operationSelectionReady()
         && operationAdjustModifierHeld();
      boolean operationAlt = operationActive && modifierHeld();
      boolean interactionTargetHit = FastPlaceClientPreview.operationGizmoHit() != null
         || pointerIntent instanceof OperationInteractionIntent.Face
         || pointerIntent instanceof OperationInteractionIntent.Part;
      return OperationInputSemantics.decideLeftPress(new OperationInputSemantics.LeftPressSnapshot(
         ClientOperationController.operationPrism()
            ? io.github.fastformer.fastplace.selection.OperationSelectionMode.PRISM
            : io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID,
         operationActive && (ClientOperationController.operationSelectionReady() || ClientOperationController.active()),
         operationActive && (ClientOperationController.operationAdjustmentStarted() || ClientOperationController.active()),
         operationActive && FastPlaceClientPreview.operationGizmoHit() != null,
         InteractionContext.nearVanillaBlock(Minecraft.getInstance()),
         operationAdjust || operationAlt,
         interactionTargetHit,
         ClientOperationController.active()
      ));
   }

   private static boolean leftMousePressAlreadyHandled() {
      return inputSession().operationClickCapturedButton == 0
         || inputSession().geometryClickCapturedButton == 0
         || inputSession().undoPressCaptured
         || inputSession().operationDrag != null && inputSession().operationDrag.mouseButton() == 0
         || inputSession().operationPointDrag != null && inputSession().operationPointDrag.mouseButton() == 0
         || workspaceGizmoDrag() != null && workspaceGizmoDrag().mouseButton() == 0
         || workspaceFaceDrag() != null && workspaceFaceDrag().mouseButton() == 0
         || inputSession().geometryGizmoDrag != null && inputSession().geometryGizmoDrag.mouseButton() == 0;
   }

   private static void finishOperationDrag(Minecraft minecraft, long releasedAtNanos) {
      if (inputSession().operationDrag != null && inputSession().operationDrag.gizmoKey() != null) {
         FastPlaceClientPreview.noteGizmoFeedback(
            inputSession().operationDrag.gizmoKey().axis(),
            inputSession().operationDrag.gizmoKey().operation(),
            inputSession().operationDrag.sentSteps(),
            inputSession().operationDrag.gizmoBaseValue()
         );
      }
      if (inputSession().operationDrag != null && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationExtendPayload.TYPE.id())) {
         int releaseSteps = inputSession().operationDrag.faceHit() == null
            ? inputSession().operationDrag.deferredClick().releaseSteps(releasedAtNanos, OPERATION_FACE_SHORT_PRESS_NANOS)
            : 0;
         PacketDistributor.sendToServer(
            new OperationExtendPayload(inputSession().operationDrag.axis(), inputSession().operationDrag.positive(), releaseSteps, true),
            new CustomPacketPayload[0]
         );
      }
      inputSession().operationDrag = null;
      finishPointerGesture();
   }

   private static OperationPointDrag finishOperationPointDrag(Minecraft minecraft) {
      OperationPointDrag finished = inputSession().operationPointDrag;
      if (finished != null && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         PacketDistributor.sendToServer(
            new OperationPointDragPayload(
               finished.pointIndex(), finished.sentTarget(), finished.constraint(), true
            ),
            new CustomPacketPayload[0]
         );
      }
      inputSession().operationPointDrag = null;
      finishPointerGesture();
      return finished;
   }

   private static void finishOperationPointClick(Minecraft minecraft, OperationPointDrag finished, long now) {
      boolean shortUnmovedClick = finished != null
         && now - finished.pressedAt() <= OPERATION_POINT_SHORT_PRESS_NANOS
         && finished.sentTarget().equals(finished.initialPoint());
      if (finished == null || !shortUnmovedClick) {
         inputSession().lastOperationPointLeftClickAt = 0L;
         inputSession().lastOperationPointLeftClickIndex = -1;
         inputSession().lastOperationPointRightClickAt = 0L;
         inputSession().lastOperationPointRightClickIndex = -1;
         return;
      }
      if (finished.pointIndex() == 0
         && FastPlaceClientPreview.operationPrismBaseOpen()
         && FastPlaceClientPreview.operationPointCount() >= 3
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ClosePathPayload.TYPE.id())) {
         PacketDistributor.sendToServer(ClosePathPayload.INSTANCE, new CustomPacketPayload[0]);
         inputSession().lastOperationPointRightClickAt = 0L;
         inputSession().lastOperationPointRightClickIndex = -1;
         inputSession().lastOperationPointLeftClickAt = 0L;
         inputSession().lastOperationPointLeftClickIndex = -1;
         inputSession().pathClose.reset();
         return;
      }
      if (finished.mouseButton() == 1) {
         boolean doubleClick = finished.pointIndex() == inputSession().lastOperationPointRightClickIndex
            && now - inputSession().lastOperationPointRightClickAt <= OPERATION_POINT_DOUBLE_CLICK_NANOS;
         if (doubleClick
            && FastPlaceClientPreview.operationPrismBaseOpen()
            && FastPlaceClientPreview.operationPointCount() >= 3
            && NetworkRegistry.hasChannel(minecraft.getConnection(), ClosePathPayload.TYPE.id())) {
            PacketDistributor.sendToServer(ClosePathPayload.INSTANCE, new CustomPacketPayload[0]);
            inputSession().lastOperationPointRightClickAt = 0L;
            inputSession().lastOperationPointRightClickIndex = -1;
            inputSession().pathClose.reset();
         } else {
            inputSession().lastOperationPointRightClickAt = now;
            inputSession().lastOperationPointRightClickIndex = finished.pointIndex();
         }
         inputSession().lastOperationPointLeftClickAt = 0L;
         inputSession().lastOperationPointLeftClickIndex = -1;
         return;
      }
      inputSession().lastOperationPointRightClickAt = 0L;
      inputSession().lastOperationPointRightClickIndex = -1;
      boolean doubleClick = finished.pointIndex() == inputSession().lastOperationPointLeftClickIndex
         && now - inputSession().lastOperationPointLeftClickAt <= OPERATION_POINT_DOUBLE_CLICK_NANOS;
      if (doubleClick) {
         sendOperationPointRemoval(minecraft, finished.pointIndex());
         inputSession().lastOperationPointLeftClickAt = 0L;
         inputSession().lastOperationPointLeftClickIndex = -1;
      } else {
         inputSession().lastOperationPointLeftClickAt = now;
         inputSession().lastOperationPointLeftClickIndex = finished.pointIndex();
      }
   }

   /**
    * Adjusts the active selection at the crosshair. A blocked adjustment shows its
    * reason. The result stays false when nothing changed, so the pointer routing
    * keeps the behavior it had before.
    */
   private static boolean adjustWorkspaceSelectionAtCrosshair(Minecraft minecraft, int mouseButton) {
      if (!ClientOperationController.active()) {
         return false;
      }
      BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
      if (hit == null) {
         return false;
      }
      ClientOperationController.AabbAdjustDecision decision =
         ClientOperationController.adjustActiveAabbPoint(mouseButton, hit.getBlockPos());
      ClientInteractionFeedback.showAabbAdjustFailure(minecraft, decision);
      return decision.adjusted();
   }

   /**
    * Uses the normal block outline ray used by Minecraft's empty-hand actions.
    * Selection must be able to target replaceable blocks such as short grass.
    */
   private static BlockHitResult longRangeSelectionBlockHit(Minecraft minecraft) {
      return longRangeBlockHit(minecraft, false);
   }

   /** Uses placement semantics, which deliberately see through replaceable blocks. */
   private static BlockHitResult longRangePlacementBlockHit(Minecraft minecraft) {
      return longRangeBlockHit(minecraft, true);
   }

   private static BlockHitResult longRangeBlockHit(Minecraft minecraft, boolean forPlacement) {
      if (minecraft.level == null || minecraft.player == null) {
         return null;
      }
      BlockHitResult hit = (forPlacement
         ? LongRangeBlockRaycast.clipForPlacement(
            minecraft.level, minecraft.player,
            minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F)
         )
         : LongRangeBlockRaycast.clip(
            minecraft.level, minecraft.player,
            minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F)
         )).hit();
      return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit : null;
   }

   private static void cancelOperationGesture(Minecraft minecraft) {
      SelectionGestureController.cancelActive(inputSession());
      inputSession().operationDrag = null;
      inputSession().operationPointDrag = null;
      inputSession().operationClickCapturedButton = -1;
      inputSession().geometryGizmoDrag = null;
      inputSession().geometryClickCapturedButton = -1;
      inputSession().undoPress.cancel();
      inputSession().undoPressCaptured = false;
      cancelPointerGesture();
      minecraft.options.keyAttack.setDown(false);
      minecraft.options.keyUse.setDown(false);
   }

   private static void cancelWorkspaceEditIfPresent() {
      SelectionGestureController.cancelActive(inputSession());
      cancelPointerGesture();
   }

   /**
    * Ends the pointer gesture that a screen takeover or a lost window focus
    * interrupted. The physical release never reaches the world handler, so the
    * gesture must end on the context change itself.
    */
   private static void suspendPointerGesture(Minecraft minecraft) {
      cancelOperationGesture(minecraft);
      inputSession().clickGestureToken = 0L;
   }

   /**
    * The integrated server pauses its sound after the cancelled screen opening,
    * because the pause call continues past the screen hook. The resume therefore
    * runs one tick later.
    */
   private static void resumeSoundAfterSuppressedPause(Minecraft minecraft) {
      if (!inputSession().suppressedPausePausedSound) {
         return;
      }
      inputSession().suppressedPausePausedSound = false;
      minecraft.getSoundManager().resume();
   }

   private static boolean pointerGestureInFlight() {
      return inputSession().operationDrag != null
         || inputSession().operationPointDrag != null
         || inputSession().geometryGizmoDrag != null
         || workspaceGizmoDrag() != null
         || workspaceFaceDrag() != null
         || inputSession().operationClickCapturedButton >= 0
         || inputSession().geometryClickCapturedButton >= 0
         || inputSession().undoPressCaptured
         || inputSession().clickGestureToken != 0L
         || inputSession().pointerGesture.kind() != PointerGestureState.Kind.NONE;
   }

   private static boolean ownsPointerGesture(PointerGestureState.Kind kind) {
      return inputSession().pointerGesture.owns(inputSession().pointerGestureToken, kind);
   }

   static void finishPointerGesture() {
      finishPointerGesture(inputSession().pointerGestureToken);
   }

   static void finishPointerGesture(long token) {
      inputSession().pointerGesture.finish(token);
      if (inputSession().pointerGestureToken == token) inputSession().pointerGestureToken = 0L;
   }

   private static void cancelPointerGesture() {
      inputSession().pointerGesture.cancel();
      inputSession().pointerGestureToken = 0L;
   }

}
