package io.github.fastformer.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.mouse.MouseDragReleaseSemantics;
import io.github.fastformer.client.input.mouse.MousePressRoutingSemantics;
import io.github.fastformer.client.input.policy.CancelInputSemantics;
import io.github.fastformer.client.input.policy.ScrollInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ClientSemanticEvent;
import io.github.fastformer.client.input.state.InputContextBoundary;
import io.github.fastformer.client.input.state.ModifierGestureState;
import io.github.fastformer.client.input.state.ObservedInputState;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.interaction.SelectionDragCapture;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.input.OperationInputSemantics;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointPress;
import io.github.fastformer.client.operation.input.SelectionPointerPress;
import io.github.fastformer.client.operation.input.SelectionScrollMove;
import io.github.fastformer.client.operation.input.SubmissionKeyboardSemantics;
import io.github.fastformer.client.operation.input.WorkspaceKeyboardSemantics;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.placement.ClientForcedPlacement;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.quickshape.QuickShapeSubmissionController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.render.interaction.OperationPointerKind;
import io.github.fastformer.client.render.interaction.OperationPointerTarget;
import io.github.fastformer.client.session.ClientSessionManager;
import io.github.fastformer.client.ui.GeometryRadialScreen;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.network.payload.geometry.CycleStageModePayload;
import io.github.fastformer.network.payload.geometry.GeometryInteractionPayload;
import io.github.fastformer.network.payload.geometry.ScrollCandidatePayload;
import io.github.fastformer.network.payload.operation.OperationInsertPointPayload;
import io.github.fastformer.network.payload.operation.OperationPointDragPayload;
import io.github.fastformer.network.payload.operation.OperationPointPayload;
import io.github.fastformer.network.payload.placement.QuitFastPlacePayload;
import io.github.fastformer.network.payload.placement.UndoFastPlacePayload;
import io.github.fastformer.network.payload.settings.ModifierStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.InputEvent.Key;
import net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre;
import net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@EventBusSubscriber(
   modid = "fastformer",
   value = {Dist.CLIENT}
)
public final class FastPlaceClientInput {
   private static final long MODIFIER_SHORT_PRESS_NANOS = 250_000_000L;
   static final long OPERATION_FACE_SHORT_PRESS_NANOS = 140_000_000L;
   private static final ClientInputSession UNBOUND_INPUT = new ClientInputSession();

   static ClientInputSession inputSession() {
      var owner = ClientSessionManager.instance().currentSession();
      return owner == null ? UNBOUND_INPUT : owner.inputSession();
   }

   public static ClientInputSession currentSession() { return inputSession(); }

   private FastPlaceClientInput() {
   }

   public static void endWorldSession() {
      io.github.fastformer.client.interaction.ClientReachGate.reset();
      HistoryConflictConfirmation.clear();
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

   public static boolean beginStartPlacementRequest(long requestId) {
      synchronizeInputState();
      return inputSession().routing.startPlacement(requestId);
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
      if (minecraft.options.keyUse.matches(event.getKey(), event.getScanCode())) {
         ClientForcedPlacement.input(minecraft.player, event.getAction());
      }
      // Escape is captured before the pause screen opens.
      if (event.getKey() == CancelInputSemantics.ESCAPE_KEY) return;
      long window = minecraft.getWindow().getWindow();
      KeyboardInputSnapshot input = KeyboardInputSnapshot.capture(
         event.getKey(), event.getScanCode(), event.getAction(), event.getModifiers(), System.nanoTime(),
         InputConstants.isKeyDown(window, 342), InputConstants.isKeyDown(window, 346),
         InputConstants.isKeyDown(window, 341), InputConstants.isKeyDown(window, 345)
      );
      if (input.action() == 1 && (input.key() == 257 || input.key() == 335)) {
         input = input.withQuickShapeSubmission(FastPlaceClientPreview.buildingSubmission().orElse(null));
      }
      if (input.key() == CancelInputSemantics.CANCEL_KEY && input.action() == CancelInputSemantics.PRESS) {
         if (HistoryConflictConfirmation.pending() != null) {
            minecraft.options.keyDrop.consumeClick();
            inputSession().postKeyboard(input);
            return;
         }
         var cancellation = captureCancel(minecraft, input);
         if (cancellation == null) return;
         input = input.withCancellation(cancellation);
         if (CancelInputSemantics.capturesVanillaDrop(cancellation,
            cancelKeyCarriesVanillaDrop(minecraft, input.key()))) minecraft.options.keyDrop.consumeClick();
      }
      inputSession().postKeyboard(input);
   }

   private static CancelInputSemantics.Decision captureCancel(Minecraft minecraft, KeyboardInputSnapshot input) {
      if (minecraft == null || minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) return null;
      ClientInputStateMachine.Dispatch route = inputSession().routing.dispatch(ClientInputStateMachine.InputKind.CANCEL);
      boolean operationRestore = ClientOperationController.reconnectRestorePending();
      boolean previewRestore = FastPlaceClientPreview.reconnectPreviewRestorePending();
      boolean owned = route == ClientInputStateMachine.Dispatch.CANCEL || inputSession().canCancelPendingSessionStart()
         || operationRestore || previewRestore;
      var decision = CancelInputSemantics.decide(input.action(), input.key(), true,
         NetworkRegistry.hasChannel(minecraft.getConnection(), QuitFastPlacePayload.TYPE.id()), owned,
         ClientOperationController.workspaceSubmissionPending(), operationRestore, previewRestore);
      return decision.command() == CancelInputSemantics.Command.IGNORE ? null : decision;
   }

   static void handleKey(Minecraft minecraft, KeyboardInputSnapshot event) {
      if (HistoryConflictConfirmation.handleKey(minecraft, event)) return;
      synchronizeInputState();
      if (QuickReplaceMode.active()) {
         if (minecraft.screen == null && event.action() == 1) {
            if (event.key() == 82 && !event.controlDown()) QuickReplaceMode.toggle(minecraft);
            else if (event.controlDown() && (event.key() == 90 || event.key() == 89)) {
               minecraft.options.keyUse.setDown(false);
               WorldHistoryCommandDispatcher.send(minecraft, event.key());
            }
         }
         return;
      }
      ClientInputStateMachine.Dispatch inputRoute = inputSession().routing.dispatch(ClientInputStateMachine.InputKind.KEY);
      boolean buildingSession = inputRoute == ClientInputStateMachine.Dispatch.BUILDING;
      boolean geometrySession = inputRoute == ClientInputStateMachine.Dispatch.GEOMETRY;
      boolean operationSession = inputRoute == ClientInputStateMachine.Dispatch.OPERATION;
      boolean altKey = event.altKey();
      boolean ctrlKey = event.controlKey();
      boolean controlDown = event.controlDown();
      if (event.action() == 1 && event.altDown() && !altKey && !ctrlKey) {
         inputSession().modifier.consume();
      }
      if (event.action() == 1 && event.key() == 257
         && (ClientOperationController.reconnectRestorePending()
            || FastPlaceClientPreview.reconnectPreviewRestorePending())
         && minecraft.player != null && minecraft.screen == null) {
         ClientOperationController.confirmReconnectRestore();
         FastPlaceClientPreview.confirmReconnectPreviewRestore();
         return;
      }
      CancelInputSemantics.Decision cancel = event.cancellation();
      if (cancel != null && ClientOperationController.workspaceSubmissionPending()) {
         ClientInteractionFeedback.show(minecraft, "fastformer.message.operation_submit_pending");
         return;
      }
      if (cancel != null && operationSession && event.key() == 81) {
         cancelOperationGesture(minecraft);
         ClientOperationController.cancelLastSelection();
         if (NetworkRegistry.hasChannel(minecraft.getConnection(), QuitFastPlacePayload.TYPE.id())) {
            PacketDistributor.sendToServer(QuitFastPlacePayload.INSTANCE, new CustomPacketPayload[0]);
         }
         return;
      }
      if (cancel != null) {
         cancelActiveSession(
            minecraft, cancel.dismissOperationRestore(), cancel.dismissPreviewRestore()
         );
         return;
      }
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         return;
      }
      if (minecraft.player != null && minecraft.screen == null && event.action() == 1) {
         if ((event.key() == 257 || event.key() == 335) && operationSession) {
            var intent = FastPlaceClientPreview.operationInteractionIntent().orElse(null);
            BlockPos candidate = ClientOperationController.remoteSelectionPointing()
               ? FastPlaceClientPreview.operationCandidatePoint()
               : intent instanceof OperationInteractionIntent.CreateSelection create ? create.point() : null;
            inputSession().undoPress.cancel();
            inputSession().undoPressCaptured = false;
            if (inputSession().modifier.held()) inputSession().modifier.consume();
            if (!ClientOperationController.confirmSelection(minecraft, candidate)) {
               ClientInteractionFeedback.showWorkspaceSubmitFailure(minecraft);
            }
            return;
         }
         if (event.key() == 82 && !controlDown
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
         WorldHistoryCommandDispatcher.send(minecraft, event.key());
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
               ? QuickShapeSubmissionController.submit(
                  minecraft, inputSession().quickShapeSubmission, event.key(), event.action(), event.quickShapeSubmission())
               : ClientPlacementRouter.confirm(minecraft);
            if (!submitted) {
                ClientInteractionFeedback.show(minecraft, "fastformer.message.placement_confirm_failed");
            }
         }
      }
   }

   /** Ends the local session for an accepted cancel. False when the phase refused. */
   private static boolean cancelActiveSession(
      Minecraft minecraft, boolean dismissOperationRestore, boolean dismissPreviewRestore
   ) {
      boolean cancellingQuickShape = inputSession().quickShapeSubmission.active()
         || inputSession().ownsQuickShapeStart()
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
            ClientOperationController.active() || ClientOperationController.selectionDraftActive()
               || ClientOperationController.remoteSelectionPointing(),
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
         case DESELECT_ALL -> ClientOperationController.deselectAllWorkspaceParts();
         case DELETE_SELECTION -> {
            var intent = FastPlaceClientPreview.operationInteractionIntent().orElse(null);
            BlockPos candidate = ClientOperationController.remoteSelectionPointing() ? FastPlaceClientPreview.operationCandidatePoint()
               : intent instanceof OperationInteractionIntent.CreateSelection create ? create.point() : null;
            ClientOperationController.deleteSelection(minecraft, candidate);
         }
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
      if (minecraft.screen != null || !modifierSubmodeAvailable(minecraft)) {
         return;
      }
      if (inputSession().routing.dispatch(ClientInputStateMachine.InputKind.KEY) == ClientInputStateMachine.Dispatch.OPERATION) {
         ClientOperationController.setAltMode(true);
      }
      boolean routed = minecraft.screen == null
         && modifierSubmodeAvailable(minecraft)
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ModifierStatePayload.TYPE.id());
      inputSession().modifier.press(occurredAtNanos, modifierCycleAvailable(minecraft), routed);
      if (routed) ModifierCommandDispatcher.press(minecraft);
   }

   /** Releases Alt even if the session ended before the physical key release. */
   private static void releaseModifierState(Minecraft minecraft, boolean allowStageCycle) {
      releaseModifierState(minecraft, allowStageCycle, System.nanoTime());
   }

   private static void releaseModifierState(Minecraft minecraft, boolean allowStageCycle, long occurredAtNanos) {
      ModifierGestureState.Release release = inputSession().modifier.release(occurredAtNanos, MODIFIER_SHORT_PRESS_NANOS);
      ClientOperationController.setAltMode(false);
      if (release.routed()) ModifierCommandDispatcher.release(minecraft);
      if (allowStageCycle && release.routed()
         && release.cycleEligible()
         && release.shortPress()
         && NetworkRegistry.hasChannel(minecraft.getConnection(), CycleStageModePayload.TYPE.id())) {
         BlockPos candidate = FastPlaceClientPreview.lineModeCandidate();
         ModifierCommandDispatcher.cycle(minecraft);
      }
   }

   private static boolean modifierSessionActive() {
      return FastPlaceClientPreview.active()
         || FastPlaceClientPreview.operationActive()
         || FastPlaceClientPreview.geometryActive()
            && (FastPlaceClientPreview.geometryAllows(GeometryAction.MODE_CYCLE)
               || FastPlaceClientPreview.geometryAllows(GeometryAction.SUBMODE));
   }

   private static boolean modifierCycleAvailable(Minecraft minecraft) {
      return FastPlaceClientPreview.geometryActive()
         ? FastPlaceClientPreview.geometryAllows(GeometryAction.MODE_CYCLE)
         : modifierSessionActive() || idleSelectionModeCycleAvailable(minecraft);
   }

   private static boolean modifierSubmodeAvailable(Minecraft minecraft) {
      return FastPlaceClientPreview.geometryActive()
         ? FastPlaceClientPreview.geometryAllows(GeometryAction.SUBMODE)
         : FastPlaceClientPreview.active()
            || FastPlaceClientPreview.operationActive()
            || FastPlaceClientPreview.buildingRaycastSubmodeAvailable()
            || idleSelectionModeCycleAvailable(minecraft);
   }

   /** A short Alt press selects the mode for the next empty-hand selection. */
   private static boolean idleSelectionModeCycleAvailable(Minecraft minecraft) {
      return minecraft.player != null
         && minecraft.player.getMainHandItem().isEmpty();
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
      if (event.isUseItem() && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
         && minecraft.player != null) {
         boolean accepted = ClientForcedPlacement.accept(minecraft.player);
         if (!accepted && minecraft.hitResult instanceof BlockHitResult hit
            && ClientForcedPlacement.consumes(minecraft.player, event.getHand(), hit)) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
      }
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
      if (QuickReplaceMode.active()) {
         if (QuickReplaceMode.canReplace(minecraft) && event.isUseItem() && minecraft.screen == null) {
            ClientPlacementRouter.quickReplace(minecraft);
            event.setSwingHand(false);
            event.setCanceled(true);
         }
         return;
      }
      if (inputSession().geometryGizmoCapture.captured() && (event.isAttack() || event.isUseItem())) {
         if (inputSession().geometryGizmoCapture.ownsInteractionButton(event.isAttack() ? 0 : 1)) {
            event.setSwingHand(false);
            event.setCanceled(true);
         }
         return;
      }
      if ((event.isAttack() || event.isUseItem())
         && inputSession().ownsGeometryPointerButton(event.isAttack() ? 0 : 1)) {
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (operationSession && (modifierHeld() || physicalAltDown(minecraft, false))
         && (event.isAttack() || event.isUseItem())) {
         return;
      }
      if (event.isUseItem()
         && buildingSession
         && !InteractionContext.nearVanillaBlock(minecraft)) {
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
            && (inputSession().operationClickCapturedButton == 0
               || FastPlaceClientPreview.operationPointUnderCrosshairIndex() >= 0)) {
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
            if (inputSession().ownsGeometryPointerButton(0)) {
               event.setSwingHand(false);
               event.setCanceled(true);
               return;
            }
         }
         if (!NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())) {
            return;
         }
         // The mouse callback owns the physical press capture. This callback only
         // suppresses the vanilla interaction path.
         event.setSwingHand(false);
         event.setCanceled(true);
      }
   }

   @SubscribeEvent
   public static void onMouseButton(Pre event) {
      long occurredAtNanos = System.nanoTime();
      Minecraft minecraft = Minecraft.getInstance();
      // A change of input owner can consume release before MouseHandler clears the vanilla key.
      if (event.getAction() == MouseButtonInputSemantics.RELEASE && minecraft.options.keyUse.matchesMouse(event.getButton())) {
         minecraft.options.keyUse.setDown(false);
         ClientForcedPlacement.input(minecraft.player, event.getAction());
      }
      if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) return;
      if (QuickReplaceMode.active()) return;
      synchronizeInputState();
      boolean physicalAlt = physicalAltDown(minecraft, false);
      if (GeometryInputController.queueGizmo(minecraft, inputSession(), event.getAction(), event.getButton())) {
         event.setCanceled(true);
         return;
      }
      if (GeometryInputController.queueInteraction(minecraft, inputSession(), event.getAction(), event.getButton())) {
         event.setCanceled(true);
         return;
      }
      if (inputSession().geometryGizmoCapture.hasPhysicalPress()) return;
      if (GeometryInputController.queuePath(minecraft, inputSession(), event.getAction(), event.getButton(), occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      if (QuickShapeMouseInputController.capture(minecraft, inputSession(), event.getAction(), event.getButton(),
         occurredAtNanos, physicalAlt)) {
         event.setCanceled(true);
         return;
      }
      if (QuickShapeMouseInputController.captureUndo(minecraft, inputSession(), event.getAction(), event.getButton(),
         occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      if (event.getAction() == MouseButtonInputSemantics.PRESS
         && inputSession().routing.state() == ClientInputStateMachine.State.IDLE
         && !ClientOperationController.active()
         && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
         && !InteractionContext.nearVanillaBlock(minecraft)
         && QuickShapeStartController.capture(minecraft, inputSession(), event.getAction(), event.getButton(),
            physicalAlt, longRangePlacementBlockHit(minecraft))) {
         event.setCanceled(true);
         return;
      }
      if (event.getAction() == MouseButtonInputSemantics.PRESS
         && inputSession().routing.state() == ClientInputStateMachine.State.IDLE
         && minecraft.player.getMainHandItem().isEmpty() && minecraft.player.isCreative()
         && FastPlaceClientPreview.enabled() && !InteractionContext.nearVanillaBlock(minecraft)
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())) {
         BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
         if (captureInitialSelection(event.getButton(), hit == null ? null : hit.getBlockPos(),
            physicalAlt, physicalCtrlDown(minecraft, false), occurredAtNanos)) {
            event.setCanceled(true);
            return;
         }
      }
      if (queueSelectionPointer(minecraft, event.getAction(), event.getButton(), occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      if (queueOperationPointDragRelease(minecraft, event.getAction(), event.getButton(), occurredAtNanos)
         || queuePointerRelease(event.getAction(), event.getButton(), occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      ClientInputStateMachine.Dispatch capturedRoute = inputSession().routing.dispatch(ClientInputStateMachine.InputKind.POINTER);
      if (capturedRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         event.setCanceled(true);
         return;
      }
      if (capturedRoute == ClientInputStateMachine.Dispatch.VANILLA
         || capturedRoute == ClientInputStateMachine.Dispatch.BUILDING) return;
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
         inputSession().operationClickCapturedButton = event.getButton();
         event.setCanceled(true);
         return;
      }
      if (inputSession().modifier.held() && event.getAction() == 1) {
         inputSession().modifier.consume();
      }
      if (modifierHeld()
         && event.getAction() == 1
         && (event.getButton() == 0 || event.getButton() == 1)
         && inputSession().operationDrag == null
         && workspaceGizmoDrag() == null
         && workspaceFaceDrag() == null) {
         if (ClientOperationController.active()) {
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
                  inputSession().operationClickCapturedButton = 0;
                  yield true;
               }
               case WORKSPACE_ADJUST_OR_CAPTURE -> {
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
                  queueOperationPointDrag(minecraft, 0, occurredAtNanos);
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
                  yield operationSession ? captureOperationPress() : beginFallbackUndoPress(minecraft);
               }
            };
         } else if (event.getAction() == MouseButtonInputSemantics.RELEASE) {
            consumed = MouseReleaseDispatcher.finish(minecraft, inputSession(), event.getAction(), event.getButton(), occurredAtNanos);
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
            consumed = target == MousePressRoutingSemantics.RightTarget.GEOMETRY_CAPTURE;
         } else if (event.getAction() == 0
            && inputSession().ownsGeometryPointerButton(1)) {
            inputSession().releaseGeometryPointerButton(1);
            consumed = true;
         } else if (event.getAction() == 0
            && inputSession().geometryGizmoDrag != null
            && inputSession().geometryGizmoDrag.mouseButton() == 1) {
            consumed = MouseReleaseDispatcher.finishGeometryRightRelease(minecraft, inputSession());
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
                  inputSession().operationClickCapturedButton = 1;
                  yield true;
               }
               case WORKSPACE_ADJUST_OR_CAPTURE -> {
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
                  queueOperationPointDrag(minecraft, 1, occurredAtNanos);
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
            consumed = MouseReleaseDispatcher.finish(minecraft, inputSession(), event.getAction(), event.getButton(), occurredAtNanos);
         }
         if (consumed) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 2
         && event.getAction() == 1
         && operationSession
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
      var target = MouseReleaseDispatcher.target(session, action, button);
      if (target == MouseDragReleaseSemantics.Target.NONE
         || !session.routing.accepts(button, session.clickGestureToken)) {
         return false;
      }
      session.postPointerRelease(new PointerReleaseSnapshot(button, occurredAtNanos,
         session.clickGestureToken, session.pointerGestureToken, target));
      return true;
   }

   private static void queueOperationPointDrag(Minecraft minecraft, int button, long occurredAtNanos) {
      OperationPointInputController.capturePress(minecraft, inputSession(), button, occurredAtNanos)
         .ifPresent(inputSession()::captureOperationPointDrag);
   }

   private static boolean queueOperationPointDragRelease(Minecraft minecraft, int action, int button, long occurredAtNanos) {
      if (action != MouseButtonInputSemantics.RELEASE) return false;
      var release = inputSession().operationPointPointer.release(button, occurredAtNanos,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F));
      if (release == null) return false;
      inputSession().postOperationPointDrag(release);
      return true;
   }

   static boolean captureInitialSelection(int button, BlockPos point, boolean alt, boolean control, long occurredAtNanos) {
      if (button < 0 || button > 2
         || inputSession().routing.state() != ClientInputStateMachine.State.IDLE
         || ClientOperationController.active() || ClientOperationController.selectionDraftActive()) return false;
      if (button == MouseButtonInputSemantics.LEFT_BUTTON
         && io.github.fastformer.client.operation.selection.SelectionToolPreference.get()
            == io.github.fastformer.fastplace.selection.OperationSelectionMode.SMART) return true;
      if (point == null) return false;
      ClientOperationController.enterLocalSelectionSession();
      inputSession().routing.observe(ClientInputStateMachine.State.SELECTING);
      inputSession().captureSelectionPress(new SelectionDraftPress(ClientOperationController.interactionScene().owner(),
         ClientOperationController.draftSelectionMode(), button, point, alt, control, occurredAtNanos));
      return true;
   }

   @SubscribeEvent
   public static void onVanillaMouseButton(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      if (event.getAction() == MouseButtonInputSemantics.PRESS && minecraft.screen == null
         && minecraft.options.keyUse.matchesMouse(event.getButton())) {
         ClientForcedPlacement.input(minecraft.player, event.getAction());
      }
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
      if (ClientOperationController.smartTool() && !(target instanceof OperationInteractionIntent.Gizmo)
         && (ClientOperationController.smartEditing() || button == MouseButtonInputSemantics.RIGHT_BUTTON)) {
         BlockPos point;
         if (button == MouseButtonInputSemantics.LEFT_BUTTON) {
            var member = io.github.fastformer.client.interaction.SmartSelectionEditView.target(minecraft, alt);
            point = member == null ? null : member.pick().position();
         } else if (alt && button == MouseButtonInputSemantics.RIGHT_BUTTON) {
            var member = io.github.fastformer.client.interaction.SmartSelectionEditView.target(minecraft, true);
            point = io.github.fastformer.client.interaction.SmartSelectionEditView.additionTarget(minecraft, member);
         } else {
            BlockHitResult hit = io.github.fastformer.client.interaction.SmartSelectionEditView.worldTarget(minecraft);
            point = hit == null ? null : hit.getBlockPos();
         }
         // A miss is consumed without creating a point command or breaking a world block.
         if (point == null) return true;
         session.captureSelectionPress(new SelectionDraftPress(ClientOperationController.interactionScene().owner(),
            ClientOperationController.draftSelectionMode(), button, point,
            alt, physicalCtrlDown(minecraft, false), occurredAtNanos));
         return true;
      }
      boolean altGizmo = MouseButtonInputSemantics.startsAltWorkspaceGizmoTransform(
         alt, target instanceof OperationInteractionIntent.Gizmo, action, button);
      if (button == MouseButtonInputSemantics.MIDDLE_BUTTON && ClientOperationController.active()
         && !ClientOperationController.canStartSelectionDraft()) {
         queueSelectionPoint(minecraft, button, occurredAtNanos);
         return true;
      }
      if (button == MouseButtonInputSemantics.MIDDLE_BUTTON && ClientOperationController.canStartSelectionDraft()
         && !(target instanceof OperationInteractionIntent.CreateSelection)) {
         BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
         if (hit == null) return false;
         target = new OperationInteractionIntent.CreateSelection(hit.getBlockPos());
      }
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
      int partId = button == MouseButtonInputSemantics.MIDDLE_BUTTON
         ? workspace.selections().topPart().map(io.github.fastformer.workspace.model.ClientSelectionPart::id).orElse(0)
         : workspace.activeId();
      BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
      inputSession().captureSelectionPress(new SelectionPointPress(ClientOperationController.interactionScene().owner(),
         partId, workspace.interactionId(partId), button, physicalCtrlDown(minecraft, false),
         hit == null ? null : hit.getBlockPos(), occurredAtNanos));
   }

   public static boolean canCreateSelection() {
      return inputSession().routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION)
         == ClientInputStateMachine.Dispatch.OPERATION;
   }

   static boolean queueSelectionDraft(
      Minecraft minecraft, OperationInteractionIntent target, int button, boolean alt, long occurredAtNanos
   ) {
      var session = inputSession();
      if (button < 0 || button > 2) return false;
      // Keep the initial points with their server owner until the selection is complete.
      if (!alt && ClientOperationController.remoteSelectionPointing()) {
         return queueRemoteSelectionContinuation(target, button);
      }
      BlockPos point;
      if (MouseButtonInputSemantics.startsAltCreateSelection(alt,
         session.routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION) == ClientInputStateMachine.Dispatch.OPERATION,
         MouseButtonInputSemantics.PRESS, button)) {
         BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
         point = hit == null ? null : hit.getBlockPos();
      } else if (!alt && (ClientOperationController.active() || ClientOperationController.selectionDraftActive() || ClientOperationController.selectionSessionActive())
         && acceptsSelectionDraftTarget(target, button)
         && target instanceof OperationInteractionIntent.CreateSelection create) {
         point = create.point();
      } else {
         return false;
      }
      session.captureSelectionPress(new SelectionDraftPress(ClientOperationController.interactionScene().owner(),
         ClientOperationController.draftSelectionMode(), button, point, alt, physicalCtrlDown(minecraft, false), occurredAtNanos));
      return true;
   }

   static boolean acceptsSelectionDraftTarget(OperationInteractionIntent target, int button) {
      return target instanceof OperationInteractionIntent.CreateSelection
         && (button != MouseButtonInputSemantics.MIDDLE_BUTTON || ClientOperationController.canStartSelectionDraft());
   }

   private static boolean queueRemoteSelectionContinuation(OperationInteractionIntent target, int button) {
      if (!ClientOperationController.remoteSelectionPointing() || !ClientOperationController.operationCuboid()
         || !(target instanceof OperationInteractionIntent.CreateSelection)) return false;
      OperationPointPayload.Role role = switch (button) {
         case MouseButtonInputSemantics.LEFT_BUTTON -> OperationPointPayload.Role.FIRST;
         case MouseButtonInputSemantics.RIGHT_BUTTON -> OperationPointPayload.Role.SECOND;
         case MouseButtonInputSemantics.MIDDLE_BUTTON -> OperationPointPayload.Role.EXTRA;
         default -> null;
      };
      if (role == null) return false;
      queueRemoteSelectionPoint(role);
      return true;
   }

   static void queueRemoteSelectionPoint(OperationPointPayload.Role role) {
      inputSession().postRemoteSelectionPoint(RemoteSelectionPointDispatcher.capture(role));
   }

   static void handleRemoteSelectionPoint(RemoteSelectionPointRequest request,
      java.util.function.Consumer<OperationPointPayload> send) {
      RemoteSelectionPointDispatcher.dispatch(request, inputSession().routing, send);
   }

   private static boolean drainQueuedInput(Minecraft minecraft) {
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
      dispatchSession.drainPhysicalEvents(contextActive, new ClientPhysicalInputDispatcher(minecraft, dispatchSession));
      return contextActive.getAsBoolean();
   }

   /** Cancelling a session must also release the server-side modifier state. */
   private static void resetModifierState(Minecraft minecraft) {
      if (inputSession().modifier.held()) {
         releaseModifierState(minecraft, false);
      }
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
         inputSession().ownsGeometryPointerButton(MouseButtonInputSemantics.LEFT_BUTTON),
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
         inputSession().ownsGeometryPointerButton(MouseButtonInputSemantics.RIGHT_BUTTON),
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
         !ClientOperationController.operationPrism() && FastPlaceClientPreview.operationPrismBaseOpen()
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
         InputWorldCleanup.clear(minecraft, inputSession());
        return;
      }
      io.github.fastformer.network.client.ClientPayloadDispatcher.onClientTick();
      inputSession().drainSubmissionEvents();
      drainQueuedInput(minecraft);
      QuickShapeSubmissionController.tick(minecraft, inputSession().quickShapeSubmission);
      ClientOperationController.onClientTick();
      ClientOperationController.sourceMask().reapply();
      FastPlaceClientPreview.onClientTick();
      synchronizeInputState();
      if (activeSession() && !InputContextBoundary.pointerContextIntact(
         pointerGestureInFlight(), minecraft.screen != null, minecraft.isWindowActive()
      )) {
         suspendPointerGesture(minecraft);
      }
      if (QuickReplaceMode.active()) {
         if (!minecraft.player.isCreative() || !FastPlaceClientPreview.enabled()) {
            QuickReplaceMode.toggle(minecraft);
         } else if (inputSession().routing.routesToVanilla(ClientInputStateMachine.InputKind.INTERACTION)
            && QuickReplaceMode.canReplace(minecraft) && minecraft.screen == null
            && minecraft.isWindowActive() && minecraft.options.keyUse.isDown()) {
            ClientPlacementRouter.quickReplace(minecraft);
         }
         return;
      }
      if (inputSession().routing.dispatch(ClientInputStateMachine.InputKind.KEY)
         != ClientInputStateMachine.Dispatch.BLOCKED) {
         pollModifierKeys(minecraft);
      }
      boolean operationActive = FastPlaceClientPreview.operationActive();
      if (inputSession().operationSessionWasActive && !operationActive) {
         minecraft.options.keyAttack.setDown(false);
      }
      inputSession().operationSessionWasActive = operationActive;
      if (!activeSession()) {
         InputSessionIdleCleanup.clear(minecraft, inputSession());
      }
      PointerDragTicker.advance(minecraft, inputSession(), physicalCtrlDown(minecraft, false));
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

   @SubscribeEvent
   public static void onMouseScroll(MouseScrollingEvent event) {
      if (QuickReplaceMode.active()) return;
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

   private static boolean beginOperationGizmoDrag(Minecraft minecraft, int mouseButton) {
      OperationInteractionIntent.Gizmo workspaceTarget = FastPlaceClientPreview.operationWorkspaceGizmoHit();
      if (workspaceTarget != null) {
         var press = SelectionPointerPress.capture(workspaceTarget, mouseButton, physicalCtrlDown(minecraft, false), 0,
            ClientOperationController.interactionScene(), ClientOperationController.workspace());
         return press.isPresent() && beginWorkspaceGizmoDrag(workspaceTarget, press.get().button(), press.get().control());
      }
      return OperationDragController.beginGizmo(minecraft, inputSession(), mouseButton);
   }

   private static boolean beginWorkspaceGizmoDrag(
      OperationInteractionIntent.Gizmo target, int mouseButton, boolean control
   ) {
      return SelectionGestureController.beginWorkspaceGizmoDrag(inputSession(), target, mouseButton, control);
   }

   private static boolean sendOperationEdgeInsertion(Minecraft minecraft) {
      return OperationPointCommandDispatcher.insertEdge(minecraft);
   }

   private static boolean sendNextOperationPrismPoint(Minecraft minecraft) {
      return OperationPointCommandDispatcher.queueNext(inputSession(), minecraft);
   }

   private static boolean sendOperationPointSelection(Minecraft minecraft) {
      return OperationPointCommandDispatcher.select(minecraft);
   }

   private static boolean beginOperationFaceAdjustment(Minecraft minecraft, int steps, int mouseButton) {
      return OperationDragController.beginFace(minecraft, inputSession(), mouseButton, steps);
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
      if (!inputSession().modifier.held() || inputSession().radialChordDown
         || !modifierSubmodeAvailable(Minecraft.getInstance())) {
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
      return QuickReplaceMode.active() || FastPlaceClientPreview.active()
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
         FastPlaceClientPreview.activity() == io.github.fastformer.fastplace.session.FastPlaceActivity.RESTORE_TASK,
         FastPlaceClientPreview.taskActive(),
         FastPlaceClientPreview.operationActive() || ClientOperationController.selectionSessionActive(),
         ClientOperationController.active(),
         ClientOperationController.operationSelectionConfirmed(),
         FastPlaceClientPreview.geometryActive(),
         FastPlaceClientPreview.active(),
         QuickReplaceMode.active()
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
         || inputSession().ownsGeometryPointerButton(0)
         || inputSession().undoPressCaptured
         || inputSession().operationDrag != null && inputSession().operationDrag.mouseButton() == 0
         || inputSession().operationPointDrag != null && inputSession().operationPointDrag.mouseButton() == 0
         || workspaceGizmoDrag() != null && workspaceGizmoDrag().mouseButton() == 0
         || workspaceFaceDrag() != null && workspaceFaceDrag().mouseButton() == 0
         || inputSession().geometryGizmoDrag != null && inputSession().geometryGizmoDrag.mouseButton() == 0;
   }

   /**
    * Uses the normal block outline ray used by Minecraft's empty-hand actions.
    * Selection must be able to target replaceable blocks such as short grass.
    */
   private static BlockHitResult longRangeSelectionBlockHit(Minecraft minecraft) {
      if (minecraft.level == null || minecraft.player == null) return null;
      var hit = LongRangeBlockRaycast.clipForSelection(minecraft.level, minecraft.player,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F), physicalAltDown(minecraft, false)).hit();
      return hit.getType() == HitResult.Type.BLOCK ? hit : null;
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
      OperationDragController.cancel(inputSession());
      OperationPointInputController.cancel(inputSession());
      inputSession().cancelPointerState();
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
         || inputSession().undoPressCaptured
         || inputSession().quickShapeUndo.captured()
         || inputSession().hasQuickShapeButtons()
         || inputSession().geometryGizmoCapture.captured()
         || inputSession().hasGeometryPointerButtons()
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
