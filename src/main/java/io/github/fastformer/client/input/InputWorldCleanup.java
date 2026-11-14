package io.github.fastformer.client.input;

import io.github.fastformer.client.interaction.binding.tool.QuickReplaceBindingInput;
import io.github.fastformer.client.interaction.selectiongesture.WorkspaceFaceDrag;
import io.github.fastformer.client.interaction.selectiongesture.WorkspaceGizmoDrag;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ClientSemanticEvent;
import io.github.fastformer.client.input.state.InputContextBoundary;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.quickshape.QuickShapeSubmissionController;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.network.inbound.ClientInboundSystem;
import net.minecraft.client.Minecraft;
import io.github.fastformer.protocol.s2c.placement.IdleInteractionSnapshotPayload;
import io.github.fastformer.protocol.s2c.placement.IdleSelectionEntryResultPayload;
import io.github.fastformer.protocol.s2c.placement.PlacementActionAckPayload;
import io.github.fastformer.protocol.s2c.preview.SmartFillPreviewPayload;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Coordinates transient routing, queued input and cleanup at world-context boundaries. */
final class InputWorldCleanup {
   private InputWorldCleanup() { }

   static void clear(Minecraft minecraft, ClientInputSession session) {
      ClientInboundSystem.endWorldSession();
      QuickReplaceMode.clear();
      session.selectionGestures().cancel();
      if (session.operationSessionWasActive) minecraft.options.keyAttack.setDown(false);
      session.reset();
      ClientSessionSystem.instance().operations().setAltMode(false);
   }

   static void endWorldSession() {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      HistoryConflictConfirmation.clear();
      ClientInboundSystem.endWorldSession();
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft != null && inputOwner.operationSessionWasActive) minecraft.options.keyAttack.setDown(false);
      cancelOperationGesture(minecraft);
      inputOwner.reset();
      QuickReplaceMode.clear();
   }

   static boolean beginPlacementRequest(long requestId) {
      synchronizeInputState();
      if (!ClientSessionSystem.instance().inputSession().routing.submit(requestId)) {
         return false;
      }
      cancelOperationGesture(Minecraft.getInstance());
      return true;
   }

   static boolean beginStartPlacementRequest(long requestId) {
      synchronizeInputState();
      return ClientSessionSystem.instance().inputSession().routing.startPlacement(requestId);
   }

   static boolean awaitsPlacementRequest(long requestId) {
      return ClientSessionSystem.instance().inputSession().routing.awaitsPlacementRequest(requestId);
   }

   static void acknowledgePlacementRequest(
      PlacementActionAckPayload payload
   ) {
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
      ClientSessionSystem.instance().inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(payload.requestId()),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, observed));
   }

   static void abortPlacementRequest(long requestId) {
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
      ClientSessionSystem.instance().inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(requestId),
         ClientInputStateMachine.SubmissionEvent.FAILED, observed));
   }

   static boolean beginWorkspaceRequest(UUID transferId) {
      synchronizeInputState();
      if (!ClientSessionSystem.instance().inputSession().routing.submit(transferId)) {
         return false;
      }
      cancelOperationGesture(Minecraft.getInstance());
      return true;
   }

   static void acknowledgeWorkspaceRequest(UUID transferId) {
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
      ClientSessionSystem.instance().inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(transferId),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, observed));
   }

   static void abortWorkspaceRequest(UUID transferId) {
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
      ClientSessionSystem.instance().inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(transferId),
         ClientInputStateMachine.SubmissionEvent.FAILED, observed));
   }

   static void expireWorkspaceRequest(UUID transferId) {
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
      ClientSessionSystem.instance().inputSession().postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Workspace(transferId),
         ClientInputStateMachine.SubmissionEvent.EXPIRED, observed));
   }

   static void receiveIdleInteraction(IdleInteractionSnapshotPayload payload) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level == null || minecraft.player == null || minecraft.screen != null
         || !minecraft.level.dimension().location().equals(payload.dimension())) return;
      inputOwner.idleEntry.validate(minecraft.level, minecraft.player.getMainHandItem());
      inputOwner.idleEntry.update(payload.token(), payload.snapshot());
   }

   static void receiveIdleSelectionEntry(IdleSelectionEntryResultPayload payload) {
      IdleSelectionEntryController.receive(Minecraft.getInstance(), ClientSessionSystem.instance().inputSession(), payload);
   }

   static boolean drainQueuedInput(Minecraft minecraft) {
      var dispatchSession = ClientSessionSystem.instance().inputSession();
      var dispatchPlayer = minecraft.player;
      var dispatchConnection = minecraft.getConnection();
      var dispatchLevel = minecraft.level;
      BooleanSupplier contextActive = () -> dispatchSession == ClientSessionSystem.instance().inputSession()
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

   static void prepareTick(Minecraft minecraft) {
      resumeSoundAfterSuppressedPause(minecraft);
   }

   static void clearWorldInput(Minecraft minecraft) {
      InputWorldCleanup.clear(minecraft, ClientSessionSystem.instance().inputSession());
   }

   static void dispatchTick(Minecraft minecraft) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      inputOwner.bindings.tick(minecraft, inputOwner, System.nanoTime());
      inputOwner.smartFill().tick(minecraft);
      inputOwner.drainSubmissionEvents();
      IdleSelectionEntryController.dispatch(minecraft, inputOwner);
      drainQueuedInput(minecraft);
      QuickShapeSubmissionController.tick(minecraft, inputOwner.quickShapeSubmission);
   }

   static void advanceTick(Minecraft minecraft) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      synchronizeInputState();
      if (minecraft.screen != null || !minecraft.isWindowActive()) {
         inputOwner.idleEntry.clear();
         inputOwner.entryPressGuard.clear();
         inputOwner.cuboidPointPressGuard.clear();
      } else if (!ClientSessionSystem.instance().modeView().idleActionsAllowed()) inputOwner.idleEntry.clear();
      else inputOwner.idleEntry.validate(minecraft.level, minecraft.player.getMainHandItem());
      if (ClientSessionSystem.instance().modeView().interactiveSession() && !InputContextBoundary.pointerContextIntact(
         pointerGestureInFlight(), minecraft.screen != null, minecraft.isWindowActive()
      )) {
         suspendPointerGesture(minecraft);
      }
       if (QuickReplaceBindingInput.tick(ClientModeKeyInput.replacementContext(minecraft, inputOwner),
          System.nanoTime(), ClientModeKeyInput.toolDispatcher(minecraft, inputOwner))) return;
      if (inputOwner.routing.dispatch(ClientInputStateMachine.InputKind.KEY)
         != ClientInputStateMachine.Dispatch.BLOCKED) {
         ModifierCommandDispatcher.pollModifierKeys(minecraft);
      }
      boolean operationActive = ClientSessionSystem.instance().operationActive();
      if (inputOwner.operationSessionWasActive && !operationActive) {
         minecraft.options.keyAttack.setDown(false);
      }
      inputOwner.operationSessionWasActive = operationActive;
      if (!ClientSessionSystem.instance().modeView().interactiveSession()) {
         InputSessionIdleCleanup.clear(minecraft, inputOwner);
      }
      PointerDragTicker.advance(minecraft, inputOwner, ModifierCommandDispatcher.physicalCtrlDown(minecraft, false));
   }

   static WorkspaceGizmoDrag workspaceGizmoDrag() {
      return ClientSessionSystem.instance().inputSession().selectionGestures().gizmo();
   }

   static WorkspaceFaceDrag workspaceFaceDrag() {
      return ClientSessionSystem.instance().inputSession().selectionGestures().face();
   }

   static void synchronizeInputState() {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      inputOwner.pointer().invalidate();
      var observed = ClientSessionSystem.instance().modeView().observedPhase();
      var routing = inputOwner.routing;
      long generation = routing.generation();
      routing.observe(observed);
      if (routing.generation() != generation) {
         cancelOperationGesture(Minecraft.getInstance());
      }
   }

   static void receiveSmartFill(SmartFillPreviewPayload payload) {
      ClientSessionSystem.instance().inputSession().smartFill().receive(payload);
      synchronizeInputState();
   }

   static void cancelOperationGesture(Minecraft minecraft) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      inputOwner.selectionGestures().cancel();
      OperationDragController.cancel(inputOwner);
      OperationPointInputController.cancel(inputOwner);
      cancelPointerGesture();
      minecraft.options.keyAttack.setDown(false);
      minecraft.options.keyUse.setDown(false);
   }

   static void suspendPointerGesture(Minecraft minecraft) {
      cancelOperationGesture(minecraft);
   }

   static void resumeSoundAfterSuppressedPause(Minecraft minecraft) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (!inputOwner.suppressedPausePausedSound) {
         return;
      }
      inputOwner.suppressedPausePausedSound = false;
      minecraft.getSoundManager().resume();
   }

   static boolean pointerGestureInFlight() {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      return inputOwner.operationDrag != null
         || inputOwner.operationPointDrag != null
         || inputOwner.geometryGizmoDrag != null
         || workspaceGizmoDrag() != null
         || workspaceFaceDrag() != null
         || inputOwner.operationClick.active()
         || inputOwner.undoPressCaptured
         || inputOwner.quickShapeUndo.captured()
         || inputOwner.hasQuickShapeButtons()
         || inputOwner.geometryGizmoCapture.captured()
         || inputOwner.hasGeometryPointerButtons()
         || inputOwner.clickGestureToken != 0L
         || inputOwner.pointerGesture.kind() != PointerGestureState.Kind.NONE;
   }

   static void finishPointerGesture() {
      finishPointerGesture(ClientSessionSystem.instance().inputSession().pointerGesture.activeToken());
   }

   static void finishPointerGesture(long token) {
      ClientSessionSystem.instance().inputSession().pointerGesture.finish(token);
   }

   static void cancelPointerGesture() {
      ClientSessionSystem.instance().inputSession().pointerGesture.cancel();
   }
}
