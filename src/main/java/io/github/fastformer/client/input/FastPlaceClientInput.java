package io.github.fastformer.client.input;

import io.github.fastformer.client.preview.frame.ModifierReticleMode;

import io.github.fastformer.client.interaction.binding.tool.QuickReplaceBindingInput;
import io.github.fastformer.client.interaction.binding.tool.SmartFillBindingInput;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.mouse.MouseDragReleaseSemantics;
import io.github.fastformer.client.mode.input.pointer.PointerDecision;
import io.github.fastformer.client.mode.input.pointer.PointerInput;
import io.github.fastformer.client.mode.input.pointer.PointerView;
import io.github.fastformer.client.input.policy.CancelInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ClientSemanticEvent;
import io.github.fastformer.client.input.state.InputContextBoundary;
import io.github.fastformer.client.input.state.ModifierGestureState;
import io.github.fastformer.client.mode.ClientModes;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.interaction.SelectionDragCapture;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.input.OperationInputSemantics;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointPress;
import io.github.fastformer.client.operation.input.SelectionPointerPress;
import io.github.fastformer.client.operation.input.SelectionScrollAction;
import io.github.fastformer.client.operation.input.SubmissionKeyboardSemantics;
import io.github.fastformer.client.operation.input.WorkspaceKeyboardSemantics;
import io.github.fastformer.client.placement.ClientForcedPlacement;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.quickshape.QuickShapeSubmissionController;
import io.github.fastformer.client.operation.pointer.OperationPointerKind;
import io.github.fastformer.client.operation.pointer.OperationPointerTarget;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.ui.GeometryRadialScreen;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.protocol.c2s.geometry.CycleStageModePayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryInteractionPayload;
import io.github.fastformer.protocol.c2s.geometry.ScrollCandidatePayload;
import io.github.fastformer.protocol.c2s.operation.OperationInsertPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointDragPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import io.github.fastformer.protocol.c2s.placement.QuitFastPlacePayload;
import io.github.fastformer.protocol.c2s.placement.UndoFastPlacePayload;
import io.github.fastformer.protocol.c2s.settings.ModifierStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
   static ClientInputSession inputSession() {
      return ClientSessionSystem.instance().inputSession();
   }

   public static ClientInputSession currentSession() { return inputSession(); }

   private FastPlaceClientInput() {
   }

   public static void endWorldSession() {
      HistoryConflictConfirmation.clear();
      io.github.fastformer.client.network.inbound.ClientInboundSystem.endWorldSession();
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
      io.github.fastformer.protocol.s2c.placement.PlacementActionAckPayload payload
   ) {
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
      inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(payload.requestId()),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, observed));
   }

   public static void abortPlacementRequest(long requestId) {
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
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
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
      inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(transferId),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, observed));
   }

   public static void abortWorkspaceRequest(java.util.UUID transferId) {
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
      inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(transferId),
         ClientInputStateMachine.SubmissionEvent.FAILED, observed));
   }

   public static void expireWorkspaceRequest(java.util.UUID transferId) {
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
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
      boolean shortcutOwnsMovementKey = false;
      if (minecraft.player != null && minecraft.screen == null) {
         synchronizeInputState();
         int shortcutAction = input.action() == 2 ? 1 : input.action();
         shortcutOwnsMovementKey = ClientModeKeyInput.workspaceOwnsKey(minecraft, inputSession(), input)
            || workspaceShortcutDecision(shortcutAction, input.key(), input.controlDown(),
            inputSession().routing.dispatch(ClientInputStateMachine.InputKind.KEY)).consumesPress();
      }
      MovementKeyInput.reconcile(new net.minecraft.client.KeyMapping[]{
         minecraft.options.keyUp, minecraft.options.keyDown, minecraft.options.keyLeft, minecraft.options.keyRight
      }, input, shortcutOwnsMovementKey);
      if (minecraft.player != null && minecraft.screen == null && input.action() == 1
          && shortcutOwnsMovementKey
         && minecraft.options.keySwapOffhand.matches(input.key(), input.scanCode())) {
         minecraft.options.keySwapOffhand.consumeClick();
      }
      if (ClientModeKeyInput.capture(minecraft, inputSession(), input)) {
         synchronizeInputState();
         return;
      }
      if (input.action() == 1 && (input.key() == 257 || input.key() == 335)) {
         input = input.withQuickShapeSubmission(ClientSessionSystem.instance().previews().buildingSubmission().orElse(null));
      }
      if (input.key() == CancelInputSemantics.CANCEL_KEY && input.action() == CancelInputSemantics.PRESS) {
         if (inputSession().bindings.onKey(minecraft, inputSession(), input.key(), input.action(), System.nanoTime())) {
            if (cancelKeyCarriesVanillaDrop(minecraft, input.key())) minecraft.options.keyDrop.consumeClick();
            return;
         }
         inputSession().idleEntry.clear();
         inputSession().entryPressGuard.clear();
         inputSession().cuboidPointPressGuard.clear();
         if (HistoryConflictConfirmation.pending() != null) {
            if (cancelKeyCarriesVanillaDrop(minecraft, input.key())) minecraft.options.keyDrop.consumeClick();
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
      synchronizeInputState();
      ClientInputStateMachine.Dispatch route = inputSession().routing.dispatch(ClientInputStateMachine.InputKind.CANCEL);
      boolean operationRestore = ClientSessionSystem.instance().operations().reconnectRestorePending();
      boolean previewRestore = ClientSessionSystem.instance().previews().reconnectRestorePending();
      boolean owned = route == ClientInputStateMachine.Dispatch.CANCEL || inputSession().canCancelPendingSessionStart()
         || operationRestore || previewRestore;
      var decision = CancelInputSemantics.decide(input.action(), input.key(), true,
         NetworkRegistry.hasChannel(minecraft.getConnection(), QuitFastPlacePayload.TYPE.id()), owned,
         ClientSessionSystem.instance().operations().workspaceSubmissionPending(), operationRestore, previewRestore);
      return decision.command() == CancelInputSemantics.Command.IGNORE ? null : decision;
   }

   static void handleKey(Minecraft minecraft, KeyboardInputSnapshot event) {
      if (inputSession().bindings.onKey(minecraft, inputSession(), event.key(), event.action(), System.nanoTime())) {
         if (cancelKeyCarriesVanillaDrop(minecraft, event.key())) minecraft.options.keyDrop.consumeClick();
         return;
      }
      if (HistoryConflictConfirmation.handleKey(minecraft, event)) return;
      synchronizeInputState();
      if (ClientModeKeyInput.handle(minecraft, inputSession(), event)) {
         synchronizeInputState();
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
         && (ClientSessionSystem.instance().operations().reconnectRestorePending()
            || ClientSessionSystem.instance().previews().reconnectRestorePending())
         && minecraft.player != null && minecraft.screen == null) {
         ClientSessionSystem.instance().operations().confirmReconnectRestore();
         ClientSessionSystem.instance().previewUpdates().confirmReconnectRestore();
         return;
      }
      CancelInputSemantics.Decision cancel = event.cancellation();
      if (cancel != null && ClientSessionSystem.instance().operations().workspaceSubmissionPending()) {
         ClientInteractionFeedback.show(minecraft, "fastformer.message.operation_submit_pending");
         return;
      }
      if (cancel != null && operationSession && event.key() == 81) {
         cancelOperationGesture(minecraft);
         ClientSessionSystem.instance().operations().selectionEdits().cancelLastSelection();
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
            var intent = ClientSessionSystem.instance().operations().pointer().resolve(InteractionContext.capture(Minecraft.getInstance())).orElse(null);
            BlockPos candidate = ClientSessionSystem.instance().operations().selectionEdits().remoteSelectionPointing()
               ? ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint()
               : intent instanceof OperationInteractionIntent.CreateSelection create ? create.point() : null;
            inputSession().undoPress.cancel();
            inputSession().undoPressCaptured = false;
            if (inputSession().modifier.held()) inputSession().modifier.consume();
            var result = ClientSessionSystem.instance().operations().confirmSelection(candidate);
            if (!result.accepted()) {
               ClientInteractionFeedback.showWorkspaceSubmitFailure(minecraft, result);
            }
            return;
         }
         if (ClientModeKeyInput.handleWorkspace(minecraft, inputSession(), event)
            || handleWorkspaceShortcut(minecraft, event.action(), event.key(), controlDown, inputRoute)) {
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
            canConfirm && InteractionContext.vanillaOwnsPointer(minecraft)
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
               if (ClientSessionSystem.instance().operations().active()) {
                  var result = ClientSessionSystem.instance().operations().submissions().submitWorkspace();
                  if (!result.accepted()) {
                     ClientInteractionFeedback.showWorkspaceSubmitFailure(minecraft, result);
                  }
               } else if (ClientSessionSystem.instance().operations().operationAdjustmentStarted()) {
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
      if (inputSession().smartFill().active()) {
         inputSession().smartFill().cancel(minecraft);
         synchronizeInputState();
         return true;
      }
      boolean cancellingQuickShape = inputSession().quickShapeSubmission.active()
         || inputSession().ownsQuickShapeStart()
         || inputSession().routing.ownsQuickShapeSubmission();
      boolean cancelled = inputSession().cancel();
      if (dismissOperationRestore) ClientSessionSystem.instance().operations().dismissReconnectRestore();
      if (dismissPreviewRestore) ClientSessionSystem.instance().previews().dismissReconnectRestore();
      if (!cancelled) {
         return false;
      }
      cancelOperationGesture(minecraft);
      resetModifierState(minecraft);
      if (!cancellingQuickShape) ClientSessionSystem.instance().operations().clearWorkspace();
      inputSession().feedback().clear();
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
      WorkspaceKeyboardSemantics.Decision decision = workspaceShortcutDecision(action, key, controlDown, keyRoute);
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
         case DELETE_SELECTION -> {
            var intent = ClientSessionSystem.instance().operations().pointer().resolve(InteractionContext.capture(Minecraft.getInstance())).orElse(null);
            BlockPos candidate = ClientSessionSystem.instance().operations().selectionEdits().remoteSelectionPointing() ? ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint()
               : intent instanceof OperationInteractionIntent.CreateSelection create ? create.point() : null;
            ClientSessionSystem.instance().operations().deletions().delete(candidate);
         }
         case REMOVE_SELECTED -> ClientSessionSystem.instance().operations().selectionEdits().removeSelectedParts();
         case UNDO -> ClientSessionSystem.instance().operations().selectionEdits().undo();
         case NONE -> {
            return false;
         }
      }
      return true;
   }

   private static WorkspaceKeyboardSemantics.Decision workspaceShortcutDecision(
      int action, int key, boolean controlDown, ClientInputStateMachine.Dispatch keyRoute
   ) {
      WorkspaceKeyboardSemantics.Command command = WorkspaceKeyboardSemantics.fromPhysicalKey(action, key, controlDown);
      return WorkspaceKeyboardSemantics.decide(
         command,
         keyRoute,
         new WorkspaceKeyboardSemantics.WorkspaceFacts(
            ClientSessionSystem.instance().operations().active() || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive()
               || ClientSessionSystem.instance().operations().selectionEdits().remoteSelectionPointing(),
            ClientSessionSystem.instance().operations().workspaceUndoDepth(),
            ClientSessionSystem.instance().operations().workspaceEditInProgress(),
            ClientSessionSystem.instance().operations().workspaceSubmissionPending()
         )
      );
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
      if (minecraft.screen != null || !modifierSubmodeAvailable(minecraft) && !modifierCycleAvailable(minecraft)) {
         return;
      }
      if (inputSession().routing.dispatch(ClientInputStateMachine.InputKind.KEY) == ClientInputStateMachine.Dispatch.OPERATION) {
         ClientSessionSystem.instance().operations().setAltMode(true);
      }
      boolean routed = minecraft.screen == null
         && modifierSubmodeAvailable(minecraft)
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ModifierStatePayload.TYPE.id());
      inputSession().modifier.press(occurredAtNanos, modifierCycleAvailable(minecraft), routed);
      if (inputSession().entryConsumedAlt) inputSession().modifier.consume();
      if (routed) ModifierCommandDispatcher.press(minecraft);
   }

   /** Releases Alt even if the session ended before the physical key release. */
   private static void releaseModifierState(Minecraft minecraft, boolean allowStageCycle) {
      releaseModifierState(minecraft, allowStageCycle, System.nanoTime());
   }

   private static void releaseModifierState(Minecraft minecraft, boolean allowStageCycle, long occurredAtNanos) {
      ModifierGestureState.Release release = inputSession().modifier.release(occurredAtNanos, MODIFIER_SHORT_PRESS_NANOS);
      inputSession().entryConsumedAlt = false;
      ClientSessionSystem.instance().operations().setAltMode(false);
      if (release.routed()) ModifierCommandDispatcher.release(minecraft);
      if (allowStageCycle && release.cyclesMode() && minecraft.screen == null) {
         ModifierCommandDispatcher.cycle(minecraft);
      }
   }

   private static boolean modifierSessionActive() {
      return ClientSessionSystem.instance().previews().building().active()
         || ClientSessionSystem.instance().operationActive()
         || ClientSessionSystem.instance().previews().geometry().active()
            && (ClientSessionSystem.instance().geometryPointer().allows(GeometryAction.MODE_CYCLE)
               || ClientSessionSystem.instance().geometryPointer().allows(GeometryAction.SUBMODE));
   }

   private static boolean modifierCycleAvailable(Minecraft minecraft) {
      return ClientSessionSystem.instance().previews().geometry().active()
         ? ClientSessionSystem.instance().geometryPointer().allows(GeometryAction.MODE_CYCLE)
         : modifierSessionActive() || idleModeCycleAvailable(minecraft);
   }

   private static boolean modifierSubmodeAvailable(Minecraft minecraft) {
      return ClientSessionSystem.instance().previews().geometry().active()
         ? ClientSessionSystem.instance().geometryPointer().allows(GeometryAction.SUBMODE)
         : ClientSessionSystem.instance().previews().building().active()
            || ClientSessionSystem.instance().operationActive()
            || ClientSessionSystem.instance().quickShapePointer().raycastSubmodeAvailable(
               ClientSessionSystem.instance().previews().geometry().active() || ClientSessionSystem.instance().previews().operation().active())
            || idleModeCycleAvailable(minecraft);
   }

   /** Idle Alt selects the next selection tool or the held block's line mode. */
   private static boolean idleModeCycleAvailable(Minecraft minecraft) {
      return minecraft.player != null
         && minecraft.player.isCreative() && ClientSessionSystem.instance().previews().building().enabled()
         && !QuickReplaceMode.active();
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
       var fill = SmartFillBindingInput.interaction(
          ClientModeKeyInput.ownsFillInput(inputSession(), ClientInputStateMachine.InputKind.INTERACTION),
          ClientModeKeyInput.fillContext(minecraft, inputSession(), false), event.isUseItem(), occurredAtNanos,
          ClientModeKeyInput.toolDispatcher(minecraft, inputSession()));
       if (fill.owns()) {
          if (fill.consumesVanilla()) {
             event.setSwingHand(false);
             event.setCanceled(true);
          }
          return;
       }
       var replacement = QuickReplaceBindingInput.interaction(ClientModeKeyInput.replacementContext(minecraft, inputSession()),
          event.isUseItem(), occurredAtNanos, ClientModeKeyInput.toolDispatcher(minecraft, inputSession()));
      if (replacement.owns()) {
         if (replacement.consumesVanilla()) {
            event.setSwingHand(false);
            event.setCanceled(true);
         }
         return;
      }
      if (inputSession().routing.state() == ClientInputStateMachine.State.IDLE) return;
      int lineButton = event.isAttack() ? 0 : event.isUseItem() ? 1 : event.isPickBlock() ? 2 : -1;
      var line = QuickShapeMouseInputController.interactionLine(minecraft, inputSession(), lineButton);
      if (line.owns()) {
         if (line.consumesVanilla()) {
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
         && !InteractionContext.vanillaOwnsPointer(minecraft)) {
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
            inputSession().operationClick.capture(0);
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
            boolean handled = !inputSession().operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON)
               && ClientSessionSystem.instance().operations().operationSelectionReady()
               && beginOperationGizmoDrag(minecraft, 0);
            if (handled) {
               inputSession().modifier.consume();
            }
            inputSession().operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (ClientSessionSystem.instance().operations().operationCuboid()) {
            if (!modifierHeld() && !InteractionContext.vanillaOwnsPointer(minecraft)) {
               OperationPointerTarget target = ClientSessionSystem.instance().operations().remotePointer().operationPointerTarget();
               if (target.kind() == OperationPointerKind.WORLD) {
                  queueCuboidPointUndo(minecraft, System.nanoTime());
                  inputSession().operationClick.capture(0);
                  event.setSwingHand(false);
                  event.setCanceled(true);
                  return;
               }
               if (target.kind() == OperationPointerKind.FACE) {
                  if (!inputSession().operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON) && beginOperationFaceAdjustment(minecraft, -1, 0)) {
                     inputSession().operationClick.capture(0);
                  }
                  event.setSwingHand(false);
                  event.setCanceled(true);
                  return;
               }
            }
         }
         if (ClientSessionSystem.instance().operations().operationPrism()
            && (inputSession().operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON)
               || ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex() >= 0)) {
            inputSession().operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!InteractionContext.vanillaOwnsPointer(minecraft)
            && ClientSessionSystem.instance().operations().operationPrism()
            && (inputSession().operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON) || sendOperationEdgeInsertion(minecraft))) {
            inputSession().operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!InteractionContext.vanillaOwnsPointer(minecraft)
            && ClientSessionSystem.instance().operations().operationPrism()
            && ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint() != null
            && (inputSession().operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON) || sendNextOperationPrismPoint(minecraft))) {
            inputSession().operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!operationSession
            && !InteractionContext.vanillaOwnsPointer(minecraft)
            && ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint() != null) {
            queueCuboidPointUndo(minecraft, System.nanoTime());
            inputSession().operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (operationSession && !InteractionContext.vanillaOwnsPointer(minecraft)) {
            inputSession().operationClick.capture(0);
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
         && !InteractionContext.vanillaOwnsPointer(minecraft)
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id())
         && ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint() != null) {
         queueRemoteSelectionPoint(nextCuboidPointRole());
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (event.isUseItem()
         && minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && geometrySession) {
         if (InteractionContext.vanillaOwnsPointer(minecraft)) {
            return;
         }
      }
      if (event.isAttack()
         && minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && (buildingSession || operationSession || geometrySession)) {
         if (InteractionContext.vanillaOwnsPointer(minecraft)) {
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
      if (event.isCanceled()) return;
      long occurredAtNanos = System.nanoTime();
      Minecraft minecraft = Minecraft.getInstance();
      // A change of input owner can consume release before MouseHandler clears the vanilla key.
      if (event.getAction() == MouseButtonInputSemantics.RELEASE && minecraft.options.keyUse.matchesMouse(event.getButton())) {
         minecraft.options.keyUse.setDown(false);
         ClientForcedPlacement.input(minecraft.player, event.getAction());
      }
       var replacement = QuickReplaceBindingInput.mouse(ClientModeKeyInput.replacementContext(minecraft, inputSession()),
          minecraft.player != null && minecraft.screen == null && minecraft.getConnection() != null,
          minecraft.options.keyUse.matchesMouse(event.getButton()), event.getAction(), event.getButton(), occurredAtNanos,
          ClientModeKeyInput.toolDispatcher(minecraft, inputSession()));
      if (replacement.consumesVanilla()) event.setCanceled(true);
      if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) {
         inputSession().bindings.cancel(minecraft, inputSession());
         return;
      }
      if (replacement.owns()) return;
      synchronizeInputState();
      boolean physicalAlt = physicalAltDown(minecraft, false);
      if (inputSession().bindings.onButton(minecraft, inputSession(), event.getAction(), event.getButton(), occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      if (event.getAction() == MouseButtonInputSemantics.RELEASE
         && inputSession().cuboidPointPressGuard.capture(event.getAction(), event.getButton(), occurredAtNanos)) {
         minecraft.options.keyUse.setDown(false);
         event.setCanceled(true);
         return;
      }
      if (IdleSessionEntryController.capture(minecraft, inputSession(), event.getAction(), event.getButton(),
         occurredAtNanos, physicalAlt, physicalCtrlDown(minecraft, false))) {
         event.setCanceled(true);
         return;
      }
       var fill = SmartFillBindingInput.mouse(
          ClientModeKeyInput.ownsFillInput(inputSession(), ClientInputStateMachine.InputKind.POINTER),
          ClientModeKeyInput.fillContext(minecraft, inputSession(), event.getButton() >= 0 && event.getButton() <= 2),
          event.getAction(), event.getButton(), occurredAtNanos, ClientModeKeyInput.toolDispatcher(minecraft, inputSession()));
       if (fill.owns()) {
          if (fill.consumesVanilla()) event.setCanceled(true);
          return;
       }
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
      var line = QuickShapeMouseInputController.captureLine(minecraft, inputSession(), event.getAction(), event.getButton(),
         occurredAtNanos, physicalAlt);
      if (line.owns()) {
         if (line.consumesVanilla()) event.setCanceled(true);
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
      OperationInteractionIntent pointerIntent = ClientSessionSystem.instance().operations().pointer().resolve(InteractionContext.capture(Minecraft.getInstance())).orElse(null);
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
         inputSession().operationClick.capture(event.getButton());
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
         if (ClientSessionSystem.instance().operations().active()) {
            inputSession().modifier.consume();
            inputSession().operationClick.capture(event.getButton());
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
               && ClientSessionSystem.instance().operations().operationSelectionReady()
               && operationAdjustModifierHeld();
            boolean operationAlt = operationSession && modifierHeld();
            OperationInputSemantics.LeftDecision leftDecision = operationLeftDecision(pointerIntent);
            PointerDecision.Left target = leftPressTarget(
               minecraft, event.getAction(), pointerIntent, leftDecision, operationAdjust, operationAlt,
               buildingSession, geometrySession, operationSession
            );
            consumed = switch (target) {
               case NONE -> false;
               case YIELD_TO_VANILLA -> false;
               case WORKSPACE_POINTER -> {
                  inputSession().operationClick.capture(0);
                  yield true;
               }
               case WORKSPACE_ADJUST_OR_CAPTURE -> {
                  // A confirmed client workspace owns the interaction surface.
                  inputSession().operationClick.capture(0);
                  yield true;
               }
               case OPERATION_GIZMO -> {
                  beginOperationGizmoDrag(minecraft, 0);
                  inputSession().operationClick.capture(0);
                  yield true;
               }
               case OPERATION_POINT -> {
                  queueOperationPointDrag(minecraft, 0, occurredAtNanos);
                  inputSession().operationClick.capture(0);
                  yield true;
               }
               case UNDO -> beginUndoPress();
               case OPERATION_ALT -> {
                  boolean handled = !inputSession().operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON) && beginOperationGizmoDrag(minecraft, 0);
                  if (handled) inputSession().modifier.consume();
                  inputSession().operationClick.capture(0);
                  yield true;
               }
               case OPERATION_ADJUST -> {
                  boolean handled = beginOperationGizmoDrag(minecraft, 0);
                  if (!handled && ClientSessionSystem.instance().operations().operationPrism()) {
                     handled = sendOperationPointSelection(minecraft)
                        || beginOperationFaceAdjustment(minecraft, -1, 0);
                  }
                  if (!inputSession().operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON) && handled) inputSession().modifier.consume();
                  inputSession().operationClick.capture(0);
                  yield true;
               }
               case CUBOID_POINT_UNDO -> {
                  queueCuboidPointUndo(minecraft, System.nanoTime());
                  inputSession().operationClick.capture(0);
                  yield true;
               }
               case CUBOID_FACE -> {
                  beginOperationFaceAdjustment(minecraft, -1, 0);
                  inputSession().operationClick.capture(0);
                  yield true;
               }
               case OPERATION_CAPTURE -> {
                  inputSession().operationClick.capture(0);
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
            PointerDecision.Right target = rightPressTarget(
               minecraft, event.getAction(), pointerIntent, true, false
            );
            if (target == PointerDecision.Right.YIELD_TO_VANILLA) return;
            consumed = target == PointerDecision.Right.GEOMETRY_CAPTURE;
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
            PointerDecision.Right target = rightPressTarget(
               minecraft, event.getAction(), pointerIntent, false, true
            );
            if (target == PointerDecision.Right.YIELD_TO_VANILLA) return;
            consumed = switch (target) {
               case NONE, YIELD_TO_VANILLA, GEOMETRY_CAPTURE, GEOMETRY_INTERACTION -> false;
               case WORKSPACE_POINTER -> {
                  inputSession().operationClick.capture(1);
                  yield true;
               }
               case WORKSPACE_ADJUST_OR_CAPTURE -> {
                  inputSession().operationClick.capture(1);
                  yield true;
               }
               case CONFIRMED_GIZMO_CAPTURE -> {
                  beginOperationGizmoDrag(minecraft, 1);
                  inputSession().operationClick.capture(1);
                  yield true;
               }
               case OPERATION_ALT -> {
                  if (beginOperationGizmoDrag(minecraft, 1)) inputSession().modifier.consume();
                  inputSession().operationClick.capture(1);
                  yield true;
               }
               case OPERATION_ADJUST -> {
                  if (beginOperationGizmoDrag(minecraft, 1)
                     || sendOperationPointSelection(minecraft)
                     || beginOperationFaceAdjustment(minecraft, 1, 1)) {
                     inputSession().modifier.consume();
                  }
                  inputSession().operationClick.capture(1);
                  yield true;
               }
               case OPERATION_POINT -> {
                  queueOperationPointDrag(minecraft, 1, occurredAtNanos);
                  inputSession().operationClick.capture(1);
                  yield true;
               }
               case OPERATION_EDGE -> {
                  sendOperationEdgeInsertion(minecraft);
                  inputSession().operationClick.capture(1);
                  yield true;
               }
               case OPERATION_NEXT_POINT -> {
                  sendNextOperationPrismPoint(minecraft);
                  inputSession().operationClick.capture(1);
                  yield true;
               }
               case CUBOID_NEXT_POINT -> {
                  if (inputSession().acceptCuboidPointPress(MouseButtonInputSemantics.RIGHT_BUTTON,
                     ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint(), occurredAtNanos)) {
                     queueRemoteSelectionPoint(nextCuboidPointRole());
                  }
                  inputSession().operationClick.capture(1);
                  yield true;
               }
               case CUBOID_FACE -> {
                  boolean handled = beginOperationFaceAdjustment(minecraft, 1, 1);
                  inputSession().operationClick.capture(1);
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
         BlockPos point = ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint();
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
         session.clickGestureToken, session.pointerGesture.activeToken(), target));
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
         || ClientSessionSystem.instance().operations().active() || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive()) return false;
      if (button == MouseButtonInputSemantics.LEFT_BUTTON
         && io.github.fastformer.client.operation.selection.SelectionToolPreference.get()
            == io.github.fastformer.fastplace.selection.OperationSelectionMode.SMART) return true;
      if (point == null) return false;
      ClientSessionSystem.instance().operations().selectionEdits().enterLocalSelectionSession();
      inputSession().routing.observe(ClientInputStateMachine.State.SELECTING);
      inputSession().captureSelectionPress(new SelectionDraftPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
         ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode(), button, point, alt, control, occurredAtNanos));
      return true;
   }

   static boolean captureSelectionEntry(BlockPos point, boolean control, long occurredAtNanos) {
      return captureInitialSelection(MouseButtonInputSemantics.RIGHT_BUTTON, point, false, control, occurredAtNanos);
   }

   public static void receiveIdleInteraction(io.github.fastformer.protocol.s2c.placement.IdleInteractionSnapshotPayload payload) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level == null || minecraft.player == null || minecraft.screen != null
         || !minecraft.level.dimension().location().equals(payload.dimension())) return;
      inputSession().idleEntry.validate(minecraft.level, minecraft.player.getMainHandItem());
      inputSession().idleEntry.update(payload.token(), payload.snapshot());
   }

   public static void receiveIdleSelectionEntry(io.github.fastformer.protocol.s2c.placement.IdleSelectionEntryResultPayload payload) {
      IdleSelectionEntryController.receive(Minecraft.getInstance(), inputSession(), payload);
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
      boolean ownSelectionDrag = session.selectionPointer.owns(session.selectionGestures().capture());
      if (action != MouseButtonInputSemantics.PRESS || pointerGestureInFlight() && !ownSelectionDrag
         || session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) != ClientInputStateMachine.Dispatch.OPERATION) {
         return false;
      }
      var target = ClientSessionSystem.instance().operations().pointer().resolve(InteractionContext.capture(Minecraft.getInstance())).orElse(null);
      boolean alt = physicalAltDown(minecraft, false);
      if (ClientSessionSystem.instance().operations().selectionEdits().smartTool() && !(target instanceof OperationInteractionIntent.Gizmo)
         && (ClientSessionSystem.instance().operations().selectionEdits().smartEditing() || button == MouseButtonInputSemantics.RIGHT_BUTTON)) {
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
         session.captureSelectionPress(new SelectionDraftPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
            ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode(), button, point,
            alt, physicalCtrlDown(minecraft, false), occurredAtNanos));
         return true;
      }
      boolean altGizmo = MouseButtonInputSemantics.startsAltWorkspaceGizmoTransform(
         alt, target instanceof OperationInteractionIntent.Gizmo, action, button);
      if (button == MouseButtonInputSemantics.MIDDLE_BUTTON && ClientSessionSystem.instance().operations().active()
         && !ClientSessionSystem.instance().operations().selectionEdits().canStartSelectionDraft()) {
         queueSelectionPoint(minecraft, button, occurredAtNanos);
         return true;
      }
      if (button == MouseButtonInputSemantics.MIDDLE_BUTTON && ClientSessionSystem.instance().operations().selectionEdits().canStartSelectionDraft()
         && !(target instanceof OperationInteractionIntent.CreateSelection)) {
         BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
         if (hit == null) return false;
         target = new OperationInteractionIntent.CreateSelection(hit.getBlockPos());
      }
      if (!altGizmo && queueSelectionDraft(minecraft, target, button, alt, occurredAtNanos)) return true;
      if (!ClientSessionSystem.instance().operations().active()) return false;
      if (alt && !altGizmo) return false;
      SelectionPressRoute route = altGizmo ? SelectionPressRoute.OBJECT : selectionPressRoute(minecraft, target, button);
      if (route == SelectionPressRoute.NONE) return false;
      if (route == SelectionPressRoute.POINT) {
         queueSelectionPoint(minecraft, button, occurredAtNanos);
         return true;
      }
      var press = SelectionPointerPress.capture(target, button, physicalCtrlDown(minecraft, false),
         button == MouseButtonInputSemantics.RIGHT_BUTTON ? 1 : -1,
         ClientSessionSystem.instance().operations().interactionScene(), ClientSessionSystem.instance().operations().workspace(), occurredAtNanos);
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
      var workspace = ClientSessionSystem.instance().operations().workspace();
      int partId = button == MouseButtonInputSemantics.MIDDLE_BUTTON
         ? workspace.selections().topPart().map(io.github.fastformer.workspace.model.ClientSelectionPart::id).orElse(0)
         : workspace.activeId();
      BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
      if (ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode() == io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID
         && !inputSession().acceptCuboidPointPress(button, hit == null ? null : hit.getBlockPos(), occurredAtNanos)) return;
      inputSession().captureSelectionPress(new SelectionPointPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
         partId, workspace.interactionId(partId), button, physicalCtrlDown(minecraft, false),
         hit == null ? null : hit.getBlockPos(), occurredAtNanos));
   }

   public static boolean canCreateSelection() {
      return inputSession().canCreateSelection();
   }

   static boolean queueSelectionDraft(
      Minecraft minecraft, OperationInteractionIntent target, int button, boolean alt, long occurredAtNanos
   ) {
      var session = inputSession();
      if (button < 0 || button > 2) return false;
      if (button == MouseButtonInputSemantics.LEFT_BUTTON
         && ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode() == io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID
         && !(target instanceof OperationInteractionIntent.Gizmo)
         && (ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive() || target == null || target instanceof OperationInteractionIntent.CreateSelection)) {
         queueCuboidPointUndo(minecraft, alt, occurredAtNanos);
         return true;
      }
      // Keep the initial points with their server owner until the selection is complete.
      if (!alt && ClientSessionSystem.instance().operations().selectionEdits().remoteSelectionPointing()) {
         return queueRemoteSelectionContinuation(target, button, occurredAtNanos);
      }
      BlockPos point;
      if (MouseButtonInputSemantics.startsAltCreateSelection(alt,
         session.routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION) == ClientInputStateMachine.Dispatch.OPERATION,
         MouseButtonInputSemantics.PRESS, button)) {
         BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
         point = hit == null ? null : hit.getBlockPos();
      } else if (!alt && (ClientSessionSystem.instance().operations().active() || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive() || ClientSessionSystem.instance().operations().selectionSessionActive())
         && acceptsSelectionDraftTarget(target, button)
         && target instanceof OperationInteractionIntent.CreateSelection create) {
         point = create.point();
      } else {
         return false;
      }
      session.captureSelectionPress(new SelectionDraftPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
         ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode(), button, point, alt, physicalCtrlDown(minecraft, false), occurredAtNanos));
      return true;
   }

   static boolean acceptsSelectionDraftTarget(OperationInteractionIntent target, int button) {
      return target instanceof OperationInteractionIntent.CreateSelection
         && (button != MouseButtonInputSemantics.MIDDLE_BUTTON || ClientSessionSystem.instance().operations().selectionEdits().canStartSelectionDraft());
   }

   private static void queueCuboidPointUndo(Minecraft minecraft, long occurredAtNanos) {
      queueCuboidPointUndo(minecraft, modifierHeld(), occurredAtNanos);
   }

   private static void queueCuboidPointUndo(Minecraft minecraft, boolean alt, long occurredAtNanos) {
      inputSession().captureSelectionPress(new SelectionDraftPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
         io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID, MouseButtonInputSemantics.LEFT_BUTTON,
         null, alt, physicalCtrlDown(minecraft, false), occurredAtNanos));
   }

   private static boolean queueRemoteSelectionContinuation(OperationInteractionIntent target, int button, long occurredAtNanos) {
      if (!ClientSessionSystem.instance().operations().selectionEdits().remoteSelectionPointing() || !ClientSessionSystem.instance().operations().operationCuboid()
         || !(target instanceof OperationInteractionIntent.CreateSelection create)) return false;
      OperationPointPayload.Role role = switch (button) {
         case MouseButtonInputSemantics.RIGHT_BUTTON -> nextCuboidPointRole();
         case MouseButtonInputSemantics.MIDDLE_BUTTON -> OperationPointPayload.Role.EXTRA;
         default -> null;
      };
      if (role == null) return false;
      if (!inputSession().acceptCuboidPointPress(button, create.point(), occurredAtNanos)) return true;
      queueRemoteSelectionPoint(role);
      return true;
   }

   private static OperationPointPayload.Role nextCuboidPointRole() {
      return ClientSessionSystem.instance().operations().remotePointer().operationNeedsFirst() ? OperationPointPayload.Role.FIRST : OperationPointPayload.Role.SECOND;
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


   private static PointerDecision.Left leftPressTarget(
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
      OperationPointerTarget pointerTarget = ClientSessionSystem.instance().operations().remotePointer().operationPointerTarget();
      boolean operationPointTarget = ClientSessionSystem.instance().operations().operationPrism()
         && inputSession().operationDrag == null
         && inputSession().operationPointDrag == null
         && ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex() >= 0
         && ClientSessionSystem.instance().operations().remotePointer().operationPointCenter(ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex()) != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id());
      boolean operationUndo = leftDecision.action() == OperationInputSemantics.LeftAction.SELECTION_UNDO
         || leftDecision.action() == OperationInputSemantics.LeftAction.ADJUSTMENT_UNDO;
      if (!buildingSession && !geometrySession && !operationSession) return PointerDecision.Left.NONE;
      var decision = ClientModes.registry().onPointer(
         pointerPhase(buildingSession, geometrySession, operationSession),
         new PointerInput(action, MouseButtonInputSemantics.LEFT_BUTTON), new PointerView.Left(
         leftDecision.yieldToVanilla(),
         workspacePointerTarget(pointerIntent),
         ClientSessionSystem.instance().operations().active(),
         leftDecision.action() == OperationInputSemantics.LeftAction.GIZMO_DRAG,
         operationPointTarget,
         operationUndo,
         operationAlt,
         operationAdjust,
         ClientSessionSystem.instance().operations().operationCuboid()
            && !InteractionContext.vanillaOwnsPointer(minecraft)
            && pointerTarget.kind() == OperationPointerKind.WORLD,
         ClientSessionSystem.instance().operations().operationCuboid()
            && !InteractionContext.vanillaOwnsPointer(minecraft)
            && pointerTarget.kind() == OperationPointerKind.FACE,
         inputSession().operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON),
         inputSession().operationDrag != null && inputSession().operationDrag.mouseButton() == MouseButtonInputSemantics.LEFT_BUTTON,
         inputSession().ownsGeometryPointerButton(MouseButtonInputSemantics.LEFT_BUTTON),
         inputSession().geometryGizmoDrag != null,
         geometrySession && ClientSessionSystem.instance().geometryPointer().gizmoHit() != null,
         geometrySession,
         NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())
      ));
      return decision instanceof PointerDecision.Left left ? left : PointerDecision.Left.NONE;
   }

   private static PointerDecision.Right rightPressTarget(
      Minecraft minecraft,
      int action,
      OperationInteractionIntent pointerIntent,
      boolean geometrySession,
      boolean operationSession
   ) {
      OperationPointerTarget pointerTarget = ClientSessionSystem.instance().operations().remotePointer().operationPointerTarget();
      boolean vanillaOwnsPointer = InteractionContext.vanillaOwnsPointer(minecraft);
      boolean operationAdjust = operationSession
         && ClientSessionSystem.instance().operations().operationSelectionReady()
         && operationAdjustModifierHeld();
      boolean operationPointTarget = ClientSessionSystem.instance().operations().operationPrism()
         && inputSession().operationDrag == null
         && inputSession().operationPointDrag == null
         && ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex() >= 0
         && ClientSessionSystem.instance().operations().remotePointer().operationPointCenter(ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex()) != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id());
      if (!geometrySession && !operationSession) return PointerDecision.Right.NONE;
      var decision = ClientModes.registry().onPointer(
         pointerPhase(false, geometrySession, operationSession),
         new PointerInput(action, MouseButtonInputSemantics.RIGHT_BUTTON), new PointerView.Right(
         vanillaOwnsPointer,
         inputSession().ownsGeometryPointerButton(MouseButtonInputSemantics.RIGHT_BUTTON),
         workspacePointerTarget(pointerIntent),
         ClientSessionSystem.instance().operations().active(),
         ClientSessionSystem.instance().operations().operationSelectionConfirmed(),
         operationAdjust,
         operationSession && modifierHeld(),
         operationPointTarget,
         !vanillaOwnsPointer
            && ClientSessionSystem.instance().operations().operationPrism()
            && ClientSessionSystem.instance().operations().remotePointer().operationPrismEdgeInsertion() != null
            && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationInsertPointPayload.TYPE.id()),
         !vanillaOwnsPointer
            && ClientSessionSystem.instance().operations().operationPrism()
            && ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint() != null
            && NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointPayload.TYPE.id()),
         ClientSessionSystem.instance().operations().operationCuboid() && pointerTarget.kind() == OperationPointerKind.WORLD,
         ClientSessionSystem.instance().operations().operationCuboid() && pointerTarget.kind() == OperationPointerKind.FACE,
         !ClientSessionSystem.instance().operations().operationPrism() && ClientSessionSystem.instance().operations().remotePointer().operationPrismBaseOpen()
      ));
      return decision instanceof PointerDecision.Right right ? right : PointerDecision.Right.NONE;
   }

   private static ClientInputStateMachine.State pointerPhase(boolean building, boolean geometry, boolean selection) {
      // These routes were captured before physical gesture setup and are still authoritative.
      return geometry ? ClientInputStateMachine.State.GEOMETRY
         : selection ? ClientInputStateMachine.State.SELECTING
         : building ? ClientInputStateMachine.State.BUILDING : ClientInputStateMachine.State.IDLE;
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
      inputSession().operationClick.capture(MouseButtonInputSemantics.LEFT_BUTTON);
      return true;
   }

   private static boolean beginFallbackUndoPress(Minecraft minecraft) {
      return NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id()) && beginUndoPress();
   }

   public static void prepareTick(Minecraft minecraft) {
      resumeSoundAfterSuppressedPause(minecraft);
   }

   public static void clearWorldInput(Minecraft minecraft) {
      InputWorldCleanup.clear(minecraft, inputSession());
   }

   public static void dispatchTick(Minecraft minecraft) {
      inputSession().bindings.tick(minecraft, inputSession(), System.nanoTime());
      inputSession().smartFill().tick(minecraft);
      inputSession().drainSubmissionEvents();
      IdleSelectionEntryController.dispatch(minecraft, inputSession());
      drainQueuedInput(minecraft);
      QuickShapeSubmissionController.tick(minecraft, inputSession().quickShapeSubmission);
   }

   public static void advanceTick(Minecraft minecraft) {
      synchronizeInputState();
      if (minecraft.screen != null || !minecraft.isWindowActive()) {
         inputSession().idleEntry.clear();
         inputSession().entryPressGuard.clear();
         inputSession().cuboidPointPressGuard.clear();
      } else if (!ClientSessionSystem.instance().modeView().idleActionsAllowed()) inputSession().idleEntry.clear();
      else inputSession().idleEntry.validate(minecraft.level, minecraft.player.getMainHandItem());
      if (ClientSessionSystem.instance().modeView().interactiveSession() && !InputContextBoundary.pointerContextIntact(
         pointerGestureInFlight(), minecraft.screen != null, minecraft.isWindowActive()
      )) {
         suspendPointerGesture(minecraft);
      }
       if (QuickReplaceBindingInput.tick(ClientModeKeyInput.replacementContext(minecraft, inputSession()),
          System.nanoTime(), ClientModeKeyInput.toolDispatcher(minecraft, inputSession()))) return;
      if (inputSession().routing.dispatch(ClientInputStateMachine.InputKind.KEY)
         != ClientInputStateMachine.Dispatch.BLOCKED) {
         pollModifierKeys(minecraft);
      }
      boolean operationActive = ClientSessionSystem.instance().operationActive();
      if (inputSession().operationSessionWasActive && !operationActive) {
         minecraft.options.keyAttack.setDown(false);
      }
      inputSession().operationSessionWasActive = operationActive;
      if (!ClientSessionSystem.instance().modeView().interactiveSession()) {
         InputSessionIdleCleanup.clear(minecraft, inputSession());
      }
      PointerDragTicker.advance(minecraft, inputSession(), physicalCtrlDown(minecraft, false));
   }

   @SubscribeEvent
   public static void onMouseScroll(MouseScrollingEvent event) {
       var replacement = QuickReplaceBindingInput.scroll(
          ClientModeKeyInput.replacementContext(Minecraft.getInstance(), inputSession()), event.getScrollDeltaY(),
          System.nanoTime(), ClientModeKeyInput.toolDispatcher(Minecraft.getInstance(), inputSession()));
      if (replacement.consumesVanilla()) event.setCanceled(true);
      if (replacement.owns()) return;
      synchronizeInputState();
      Minecraft minecraft = Minecraft.getInstance();
       var fill = SmartFillBindingInput.scroll(
          ClientModeKeyInput.ownsFillInput(inputSession(), ClientInputStateMachine.InputKind.SCROLL),
          ClientModeKeyInput.fillContext(minecraft, inputSession(), false), event.getScrollDeltaY(), System.nanoTime(),
          ClientModeKeyInput.toolDispatcher(minecraft, inputSession()));
       if (fill.owns()) {
          if (fill.consumesVanilla()) event.setCanceled(true);
          return;
       }
      var line = QuickShapeMouseInputController.scrollLine(minecraft, inputSession(), event.getScrollDeltaY(), System.nanoTime());
      if (line.owns()) {
         if (line.consumesVanilla()) event.setCanceled(true);
         return;
      }
      boolean sessionReady = minecraft.player != null && minecraft.screen == null;
      boolean candidateChannelAvailable = minecraft.getConnection() != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ScrollCandidatePayload.TYPE.id());
      var scroll = ClientModes.registry().onScroll(inputSession().routing.state(),
         new io.github.fastformer.client.mode.input.ScrollInput(event.getScrollDeltaY()),
         new io.github.fastformer.client.mode.input.ScrollView(
         sessionReady && ClientSessionSystem.instance().operations().active(),
         sessionReady && candidateChannelAvailable && ClientSessionSystem.instance().usesScrollContext(),
         modifierInputRoute(ModifierInput.SCROLL) == ModifierInputRoute.VANILLA,
         sessionReady && InteractionContext.vanillaOwnsPointer(minecraft))
      );
      switch (scroll.command()) {
         case BLOCK -> event.setCanceled(true);
         case WORKSPACE_TRANSFORM -> {
            BlockPos offset = OperationGeometry.viewAxisStep(minecraft.player.getViewVector(1.0F), scroll.direction());
            var workspace = ClientSessionSystem.instance().operations().workspace();
            var reference = workspace.selectedParts().stream()
               .min(java.util.Comparator.comparingInt(io.github.fastformer.workspace.model.ClientSelectionPart::id)).orElse(null);
            SelectionScrollAction.Gesture gesture = physicalCtrlDown(minecraft, false) && reference != null
               ? SelectionScrollAction.stackAlongView(reference.transform(), minecraft.player.getViewVector(1.0F), scroll.direction())
               : new SelectionScrollAction.Move(offset);
            var action = SelectionScrollAction.capture(ClientSessionSystem.instance().operations().interactionScene().owner(),
               ClientSessionSystem.instance().operations().workspace(), gesture);
            if (action.isPresent() && !ClientSessionSystem.instance().operations().workspaceSubmissionPending()) {
               inputSession().postScroll(new ScrollInputSnapshot(scroll.direction(), action.orElseThrow()));
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
      if (ClientSessionSystem.instance().operations().operationSelectionConfirmed()) {
         return ModifierInputRoute.SESSION;
      }
      return inputSession().modifier.held() && input == ModifierInput.SCROLL
         ? ModifierInputRoute.VANILLA
         : ModifierInputRoute.SESSION;
   }

   private static boolean beginOperationGizmoDrag(Minecraft minecraft, int mouseButton) {
      OperationInteractionIntent.Gizmo workspaceTarget = ClientSessionSystem.instance().operations().pointer().gizmo(InteractionContext.capture(Minecraft.getInstance()));
      if (workspaceTarget != null) {
         var press = SelectionPointerPress.capture(workspaceTarget, mouseButton, physicalCtrlDown(minecraft, false), 0,
            ClientSessionSystem.instance().operations().interactionScene(), ClientSessionSystem.instance().operations().workspace());
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
         ClientSessionSystem.instance().operations().setAltMode(false);
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
         && !ClientSessionSystem.instance().previews().building().active()
         && !ClientSessionSystem.instance().operationActive()
         && (!ClientSessionSystem.instance().previews().geometry().active() || ClientSessionSystem.instance().geometryPointer().awaitingFirstPoint())) {
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
      var minecraft = Minecraft.getInstance();
      var sessions = ClientSessionSystem.instance();
      var player = minecraft.player;
      return io.github.fastformer.client.input.policy.ModifierReticlePolicy.resolve(sessions.previews().building(),
         sessions.previews().geometry(), sessions.previews().operation().active(), sessions.modeView().idleActionsAllowed(),
         player != null && player.isCreative(), QuickReplaceMode.active(), player != null && minecraft.getCameraEntity() == player,
         player != null && PlaceableItems.isPlaceable(player.getMainHandItem()));
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
         ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().workspace(), inputSession().pointerGesture.activeToken()
      );
   }

   private static WorkspaceGizmoDrag workspaceGizmoDrag() {
      return inputSession().selectionGestures().gizmo();
   }

   private static WorkspaceFaceDrag workspaceFaceDrag() {
      return inputSession().selectionGestures().face();
   }

   /** Refreshes the routing phase before every event, including same-tick task transitions. */
   private static void synchronizeInputState() {
      inputSession().pointer().invalidate();
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
      var routing = inputSession().routing;
      long generation = routing.generation();
      routing.observe(observed);
      if (routing.generation() != generation) {
         cancelOperationGesture(Minecraft.getInstance());
      }
   }

   public static void receiveSmartFill(io.github.fastformer.protocol.s2c.preview.SmartFillPreviewPayload payload) {
      inputSession().smartFill().receive(payload);
      synchronizeInputState();
   }

   private static OperationInteractionIntent pointerIntent() {
      return ClientSessionSystem.instance().operations().pointer().resolve(InteractionContext.capture(Minecraft.getInstance())).orElse(null);
   }

   private static OperationInputSemantics.LeftDecision operationLeftDecision(
      OperationInteractionIntent pointerIntent
   ) {
      boolean operationActive = ClientSessionSystem.instance().operationActive();
      boolean operationAdjust = operationActive
         && ClientSessionSystem.instance().operations().operationSelectionReady()
         && operationAdjustModifierHeld();
      boolean operationAlt = operationActive && modifierHeld();
      boolean gizmoHit = pointerIntent instanceof OperationInteractionIntent.Gizmo
         || !ClientSessionSystem.instance().operations().active() && OperationDragController.gizmoHit(Minecraft.getInstance()) != null;
      boolean interactionTargetHit = gizmoHit
         || pointerIntent instanceof OperationInteractionIntent.Face
         || pointerIntent instanceof OperationInteractionIntent.Part;
      return OperationInputSemantics.decideLeftPress(new OperationInputSemantics.LeftPressSnapshot(
         ClientSessionSystem.instance().operations().operationPrism()
            ? io.github.fastformer.fastplace.selection.OperationSelectionMode.PRISM
            : io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID,
         operationActive && (ClientSessionSystem.instance().operations().operationSelectionReady() || ClientSessionSystem.instance().operations().active()),
         operationActive && (ClientSessionSystem.instance().operations().operationAdjustmentStarted() || ClientSessionSystem.instance().operations().active()),
         operationActive && gizmoHit,
         InteractionContext.vanillaOwnsPointer(Minecraft.getInstance()),
         operationAdjust || operationAlt,
         interactionTargetHit,
         ClientSessionSystem.instance().operations().active()
      ));
   }

   private static boolean leftMousePressAlreadyHandled() {
      return inputSession().operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON)
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
      var hit = inputSession().pointer().selection(minecraft.player, physicalAltDown(minecraft, false)).hit();
      return hit.getType() == HitResult.Type.BLOCK ? hit : null;
   }

   private static void cancelOperationGesture(Minecraft minecraft) {
      SelectionGestureController.cancelActive(inputSession());
      OperationDragController.cancel(inputSession());
      OperationPointInputController.cancel(inputSession());
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
         || inputSession().operationClick.active()
         || inputSession().undoPressCaptured
         || inputSession().quickShapeUndo.captured()
         || inputSession().hasQuickShapeButtons()
         || inputSession().geometryGizmoCapture.captured()
         || inputSession().hasGeometryPointerButtons()
         || inputSession().clickGestureToken != 0L
         || inputSession().pointerGesture.kind() != PointerGestureState.Kind.NONE;
   }

   private static boolean ownsPointerGesture(PointerGestureState.Kind kind) {
      return inputSession().pointerGesture.owns(inputSession().pointerGesture.activeToken(), kind);
   }

   static void finishPointerGesture() {
      finishPointerGesture(inputSession().pointerGesture.activeToken());
   }

   static void finishPointerGesture(long token) {
      inputSession().pointerGesture.finish(token);
   }

   private static void cancelPointerGesture() {
      inputSession().pointerGesture.cancel();
   }

}
