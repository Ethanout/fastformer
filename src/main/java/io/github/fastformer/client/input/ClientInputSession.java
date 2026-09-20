package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.client.session.ClientTickMailbox;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Transient input state owned by one player environment, never persisted with a draft. */
public final class ClientInputSession {
   final io.github.fastformer.client.quickshape.QuickShapeSubmissionIntent quickShapeSubmission =
      new io.github.fastformer.client.quickshape.QuickShapeSubmissionIntent();
   private final ClientTickMailbox<ClientSemanticEvent.SubmissionCompleted> submissionEvents =
      new ClientTickMailbox<>(event -> { });
   private final PhysicalInputMailbox physicalEvents = new PhysicalInputMailbox();
   final ShortPressTracker undoPress = new ShortPressTracker();
   final SelectionPointerCapture selectionPointer = new SelectionPointerCapture();
   final PhysicalPressGate buildingRightPress = new PhysicalPressGate();
   final PointerGestureState pointerGesture = new PointerGestureState();
   final ClientInputStateMachine routing = new ClientInputStateMachine();
   final ModifierGestureState modifier = new ModifierGestureState();
   long pointerGestureToken;
   long clickGestureToken;
   OperationDrag operationDrag;
   OperationPointDrag operationPointDrag;
   int operationClickCapturedButton = -1;
   GeometryGizmoDrag geometryGizmoDrag;
   boolean undoPressCaptured;
   int geometryClickCapturedButton = -1;
   boolean radialChordDown;
   final PathCloseGesture pathClose = new PathCloseGesture();
   long lastOperationPointLeftClickAt;
   int lastOperationPointLeftClickIndex = -1;
   long lastOperationPointRightClickAt;
   int lastOperationPointRightClickIndex = -1;
   boolean operationSessionWasActive;
   boolean suppressedPausePausedSound;

   public boolean blocksDraftLoad() {
      boolean activeOperation = switch (this.routing.state()) {
         case IDLE, SELECTING, ADJUSTING -> false;
         default -> true;
      };
      return activeOperation
         || this.physicalEvents.hasUnfinishedEvents() || this.submissionEvents.hasUnfinishedEvents()
         || this.selectionPointer.active()
         || this.pointerGesture.kind() != PointerGestureState.Kind.NONE
         || this.clickGestureToken != 0L || this.undoPressCaptured || this.modifier.held();
   }

   public void reset() {
      this.quickShapeSubmission.cancel();
      this.submissionEvents.invalidate();
      this.physicalEvents.invalidate();
      this.selectionPointer.clear();
      this.routing.reset();
      this.pointerGesture.cancel();
      this.undoPress.cancel();
      this.buildingRightPress.release();
      this.modifier.reset();
      this.pointerGestureToken = 0L;
      this.clickGestureToken = 0L;
      this.operationDrag = null;
      this.operationPointDrag = null;
      this.operationClickCapturedButton = -1;
      this.geometryGizmoDrag = null;
      this.undoPressCaptured = false;
      this.geometryClickCapturedButton = -1;
      this.radialChordDown = false;
      this.pathClose.reset();
      this.lastOperationPointLeftClickAt = 0L;
      this.lastOperationPointLeftClickIndex = -1;
      this.lastOperationPointRightClickAt = 0L;
      this.lastOperationPointRightClickIndex = -1;
      this.operationSessionWasActive = false;
      this.suppressedPausePausedSound = false;
   }

   boolean cancel() {
      if (!this.routing.cancel() && !canCancelPendingRemotePoint()) return false;
      this.quickShapeSubmission.cancel();
      discardPhysicalEvents();
      return true;
   }

   boolean canCancelPendingRemotePoint() {
      return this.physicalEvents.hasPendingRemotePoints() && this.routing.state() == ClientInputStateMachine.State.IDLE;
   }

   void discardPhysicalEvents() {
      this.physicalEvents.invalidate();
      this.selectionPointer.clear();
   }

   /** Releases all pointer-owned state without touching the active draft. */
   void cancelPointerState() {
      this.selectionPointer.clear();
      this.pointerGesture.cancel();
      this.operationDrag = null;
      this.operationPointDrag = null;
      this.operationClickCapturedButton = -1;
      this.geometryGizmoDrag = null;
      this.geometryClickCapturedButton = -1;
      this.undoPress.cancel();
      this.undoPressCaptured = false;
      this.clickGestureToken = 0L;
      this.pointerGestureToken = 0L;
   }

   void postSubmissionCompleted(ClientSemanticEvent.SubmissionCompleted event) {
      this.submissionEvents.post(event);
   }

   void postKeyboard(KeyboardInputSnapshot event) {
      this.physicalEvents.postKeyboard(event);
   }

   boolean hasQueuedPhysicalInput() {
      return this.physicalEvents.hasUnfinishedEvents();
   }

   void postScroll(ScrollInputSnapshot event) {
      this.physicalEvents.postScroll(event);
   }

   void postSelectionPointer(SelectionPointerEvent event) {
      this.physicalEvents.postSelectionPointer(event);
   }

   void postRemoteSelectionPoint(RemoteSelectionPointRequest request) {
      this.physicalEvents.postRemoteSelectionPoint(request);
   }

   SelectionPointerEvent.Press captureSelectionPress(SelectionPointerPress snapshot) {
      return captureSelectionPress(snapshot, false);
   }

   SelectionPointerEvent.Press captureSelectionPress(SelectionPointerPress snapshot, boolean alt) {
      cancelSupersededSelectionPress();
      var press = this.selectionPointer.press(snapshot, alt);
      postSelectionPointer(press);
      return press;
   }

   SelectionPointerEvent.CreatePress captureSelectionPress(SelectionDraftPress snapshot) {
      cancelSupersededSelectionPress();
      var press = this.selectionPointer.press(snapshot);
      postSelectionPointer(press);
      return press;
   }

   SelectionPointerEvent.PointPress captureSelectionPress(SelectionPointPress snapshot) {
      cancelSupersededSelectionPress();
      var press = this.selectionPointer.press(snapshot);
      postSelectionPointer(press);
      return press;
   }

   private void cancelSupersededSelectionPress() {
      var superseded = this.selectionPointer.supersededPress();
      if (superseded != null) postSelectionPointer(superseded);
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls) {
      drainPhysicalEvents(contextActive, keys, scrolls, event -> {
         throw new IllegalStateException("A selection pointer consumer is required");
      });
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer) {
      drainPhysicalEvents(contextActive, keys, scrolls, selectionPointer, event -> {
         throw new IllegalStateException("A remote selection point consumer is required");
      });
   }

   void postPointerRelease(PointerReleaseSnapshot event) {
      this.physicalEvents.postPointerRelease(event);
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer, Consumer<RemoteSelectionPointRequest> remotePoints) {
      drainPhysicalEvents(contextActive, keys, scrolls, selectionPointer, remotePoints, event -> {
         throw new IllegalStateException("A pointer release consumer is required");
      });
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer, Consumer<RemoteSelectionPointRequest> remotePoints,
      Consumer<PointerReleaseSnapshot> pointerReleases) {
      this.physicalEvents.drain(contextActive, this::discardPhysicalEvents,
         keys, scrolls, selectionPointer, remotePoints, pointerReleases);
   }

   void drainSubmissionEvents() {
      this.submissionEvents.drain(event -> {
         switch (event.request()) {
            case ClientSemanticEvent.Submit.Placement placement ->
               this.routing.completeSubmission(placement.requestId(), event.outcome(), event.observed());
            case ClientSemanticEvent.Submit.Workspace workspace ->
               this.routing.completeSubmission(workspace.transferId(), event.outcome(), event.observed());
         }
      });
   }
}
