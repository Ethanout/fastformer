package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.client.session.ClientTickMailbox;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;
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
   final QuickShapeUndoGesture quickShapeUndo = new QuickShapeUndoGesture();
   final SelectionPointerCapture selectionPointer = new SelectionPointerCapture();
   final OperationPointDragCapture operationPointPointer = new OperationPointDragCapture();
   final GeometryGizmoCapture geometryGizmoCapture = new GeometryGizmoCapture();
   private final boolean[] quickShapeButtons = new boolean[3];
   private final boolean[] geometryPointerButtons = new boolean[2];

   final PointerGestureState pointerGesture = new PointerGestureState();
   final ClientInputStateMachine routing = new ClientInputStateMachine();
   final ModifierGestureState modifier = new ModifierGestureState();
   long pointerGestureToken;
   long clickGestureToken;
   OperationDrag operationDrag;
   OperationPointDrag operationPointDrag;
   int operationClickCapturedButton = -1;
   GeometryGizmoDrag geometryGizmoDrag;
   OperationTransformCapture operationTransformCapture;
   record OperationTransformCapture(long gestureId, long revision,
      io.github.fastformer.network.payload.operation.OperationCallbackScope scope) { }
   boolean undoPressCaptured;
   boolean radialChordDown;
   final PathCloseGesture pathClose = new PathCloseGesture();
   long lastOperationPointLeftClickAt;
   int lastOperationPointLeftClickIndex = -1;
   long lastOperationPointRightClickAt;
   int lastOperationPointRightClickIndex = -1;
   boolean operationSessionWasActive;
   boolean suppressedPausePausedSound;

   boolean captureQuickShapeButton(int button) {
      if (button < 0 || button >= this.quickShapeButtons.length || this.quickShapeButtons[button]) return false;
      this.quickShapeButtons[button] = true;
      return true;
   }

   boolean releaseQuickShapeButton(int button) {
      if (button < 0 || button >= this.quickShapeButtons.length || !this.quickShapeButtons[button]) return false;
      this.quickShapeButtons[button] = false;
      return true;
   }

   boolean hasQuickShapeButtons() {
      return this.quickShapeButtons[0] || this.quickShapeButtons[1] || this.quickShapeButtons[2];
   }

   public boolean blocksDraftLoad() {
      boolean activeOperation = switch (this.routing.state()) {
         case IDLE, SELECTING, ADJUSTING -> false;
         default -> true;
      };
      return activeOperation
         || this.physicalEvents.hasUnfinishedEvents() || this.submissionEvents.hasUnfinishedEvents()
         || this.selectionPointer.active()
         || this.pointerGesture.kind() != PointerGestureState.Kind.NONE
         || this.clickGestureToken != 0L || this.undoPressCaptured || this.quickShapeUndo.captured()
         || hasQuickShapeButtons() || this.geometryGizmoCapture.captured() || hasGeometryPointerButtons() || this.modifier.held();
   }

   public void reset() {
      cancelQueuedGeometryInput();
      java.util.Arrays.fill(this.quickShapeButtons, false);
      this.quickShapeUndo.cancel();
      this.quickShapeSubmission.cancel();
      this.submissionEvents.invalidate();
      this.physicalEvents.invalidate();
      this.selectionPointer.clear();
      this.operationPointPointer.clear();
      this.routing.reset();
      this.pointerGesture.cancel();
      this.undoPress.cancel();
      this.modifier.reset();
      this.pointerGestureToken = 0L;
      this.clickGestureToken = 0L;
      this.operationDrag = null;
      this.operationPointDrag = null;
      this.operationClickCapturedButton = -1;
      this.geometryGizmoDrag = null;
      this.undoPressCaptured = false;
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
      if (!this.routing.cancel() && !canCancelPendingSessionStart()) return false;
      this.quickShapeSubmission.cancel();
      discardPhysicalEvents();
      return true;
   }

   boolean canCancelPendingSessionStart() {
      return (this.physicalEvents.hasPendingRemotePoints() || this.physicalEvents.hasPendingStarts())
         && this.routing.state() == ClientInputStateMachine.State.IDLE;
   }

   boolean ownsQuickShapeStart() {
      return this.physicalEvents.hasPendingStarts();
   }

   void discardPhysicalEvents() {
      cancelQueuedGeometryInput();
      java.util.Arrays.fill(this.quickShapeButtons, false);
      this.quickShapeUndo.cancel();
      this.physicalEvents.invalidate();
      this.selectionPointer.clear();
      this.operationPointPointer.clear();
      this.undoPress.cancel();
      this.undoPressCaptured = false;
   }

   /** Releases all pointer-owned state without touching the active draft. */
   void cancelPointerState() {
      cancelQueuedGeometryInput();
      java.util.Arrays.fill(this.quickShapeButtons, false);
      this.quickShapeUndo.cancel();
      this.selectionPointer.clear();
      this.operationPointPointer.clear();
      this.pointerGesture.cancel();
      this.operationDrag = null;
      this.operationPointDrag = null;
      this.operationClickCapturedButton = -1;
      this.geometryGizmoDrag = null;
      this.operationTransformCapture = null;
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

   void postQuickShapeUndo(QuickShapeUndoGesture.Event event) {
      this.physicalEvents.postQuickShapeUndo(event);
   }

   void postQuickShapePointer(QuickShapePointerPress event) {
      this.physicalEvents.postQuickShapePointer(event);
   }

   void postStartPlacement(StartPlacementPayload.Target target) {
      this.physicalEvents.postStartPlacement(target);
   }

   void postGeometryGizmo(GeometryGizmoCapture.Event event) {
      this.physicalEvents.postGeometryGizmo(event);
   }

   private void cancelQueuedGeometryInput() {
      java.util.Arrays.fill(this.geometryPointerButtons, false);
      if (this.geometryGizmoDrag != null && this.geometryGizmoCapture.owns(this.geometryGizmoDrag.captureToken())) {
         long token = this.geometryGizmoDrag.captureToken();
         this.pointerGesture.finish(token);
         if (this.pointerGestureToken == token) this.pointerGestureToken = 0;
         this.geometryGizmoDrag = null;
      }
      this.geometryGizmoCapture.cancel();
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
      drainPhysicalEvents(contextActive, keys, scrolls, selectionPointer, remotePoints, pointerReleases,
         event -> { throw new IllegalStateException("A quick-shape pointer consumer is required"); });
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer, Consumer<RemoteSelectionPointRequest> remotePoints,
      Consumer<PointerReleaseSnapshot> pointerReleases, Consumer<QuickShapePointerPress> quickShapePointer) {
      drainPhysicalEvents(contextActive, keys, scrolls, selectionPointer, remotePoints, pointerReleases, quickShapePointer,
         event -> { throw new IllegalStateException("A quick-shape undo consumer is required"); });
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer, Consumer<RemoteSelectionPointRequest> remotePoints,
      Consumer<PointerReleaseSnapshot> pointerReleases, Consumer<QuickShapePointerPress> quickShapePointer,
      Consumer<QuickShapeUndoGesture.Event> quickShapeUndo) {
      drainPhysicalEvents(contextActive, keys, scrolls, selectionPointer, remotePoints, pointerReleases,
         quickShapePointer, quickShapeUndo,
         event -> { throw new IllegalStateException("A start placement consumer is required"); });
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer, Consumer<RemoteSelectionPointRequest> remotePoints,
      Consumer<PointerReleaseSnapshot> pointerReleases, Consumer<QuickShapePointerPress> quickShapePointer,
      Consumer<QuickShapeUndoGesture.Event> quickShapeUndo,
      Consumer<StartPlacementPayload.Target> starts) {
      drainPhysicalEvents(contextActive, keys, scrolls, selectionPointer, remotePoints, pointerReleases,
         quickShapePointer, quickShapeUndo, starts,
         event -> { throw new IllegalStateException("A geometry gizmo consumer is required"); });
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer, Consumer<RemoteSelectionPointRequest> remotePoints,
      Consumer<PointerReleaseSnapshot> pointerReleases, Consumer<QuickShapePointerPress> quickShapePointer,
      Consumer<QuickShapeUndoGesture.Event> quickShapeUndo,
      Consumer<StartPlacementPayload.Target> starts, Consumer<GeometryGizmoCapture.Event> geometryGizmos) {
      drainPhysicalEvents(contextActive, keys, scrolls, selectionPointer, remotePoints, pointerReleases,
         quickShapePointer, quickShapeUndo, starts, geometryGizmos,
         event -> { throw new IllegalStateException("A geometry interaction consumer is required"); },
         event -> { throw new IllegalStateException("An operation point drag consumer is required"); });
   }

   void postGeometryPointer(GeometryInputController.PointerPress event) {
      this.physicalEvents.postGeometryPointer(event);
   }

   void postOperationPointDrag(OperationPointDragEvent event) {
      this.physicalEvents.postOperationPointDrag(event);
   }

   void postOperationPointCommand(OperationPointCommandEvent event) {
      this.physicalEvents.postOperationPointCommand(event);
   }

   void captureOperationPointCommand(OperationPointCommandPress press) {
      postOperationPointCommand(new OperationPointCommandEvent.Press(press));
   }

   void captureOperationPointDrag(OperationPointDragPress snapshot) {
      var superseded = this.operationPointPointer.superseded();
      if (superseded != null) postOperationPointDrag(superseded);
      postOperationPointDrag(new OperationPointDragEvent.Press(this.operationPointPointer.capture(snapshot)));
   }

   void captureGeometryPointerButton(int button) {
      this.geometryPointerButtons[button] = true;
   }

   boolean ownsGeometryPointerButton(int button) {
      return button >= 0 && button < this.geometryPointerButtons.length && this.geometryPointerButtons[button];
   }

   boolean hasGeometryPointerButtons() {
      return this.geometryPointerButtons[0] || this.geometryPointerButtons[1];
   }

   boolean releaseGeometryPointerButton(int button) {
      if (!ownsGeometryPointerButton(button)) return false;
      this.geometryPointerButtons[button] = false;
      return true;
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer, Consumer<RemoteSelectionPointRequest> remotePoints,
      Consumer<PointerReleaseSnapshot> pointerReleases, Consumer<QuickShapePointerPress> quickShapePointer,
      Consumer<QuickShapeUndoGesture.Event> quickShapeUndo,
      Consumer<StartPlacementPayload.Target> starts, Consumer<GeometryGizmoCapture.Event> geometryGizmos,
      Consumer<GeometryInputController.PointerPress> geometryPointers) {
      drainPhysicalEvents(contextActive, keys, scrolls, selectionPointer, remotePoints, pointerReleases,
         quickShapePointer, quickShapeUndo, starts, geometryGizmos, geometryPointers,
         event -> { throw new IllegalStateException("An operation point drag consumer is required"); });
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer, Consumer<RemoteSelectionPointRequest> remotePoints,
      Consumer<PointerReleaseSnapshot> pointerReleases, Consumer<QuickShapePointerPress> quickShapePointer,
      Consumer<QuickShapeUndoGesture.Event> quickShapeUndo,
      Consumer<StartPlacementPayload.Target> starts, Consumer<GeometryGizmoCapture.Event> geometryGizmos,
      Consumer<GeometryInputController.PointerPress> geometryPointers,
      Consumer<OperationPointDragEvent> operationPointDrags) {
      drainPhysicalEvents(contextActive, keys, scrolls, selectionPointer, remotePoints, pointerReleases,
         quickShapePointer, quickShapeUndo, starts, geometryGizmos, geometryPointers, operationPointDrags,
         event -> { throw new IllegalStateException("An operation point command consumer is required"); });
   }

   void drainPhysicalEvents(BooleanSupplier contextActive,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer, Consumer<RemoteSelectionPointRequest> remotePoints,
      Consumer<PointerReleaseSnapshot> pointerReleases, Consumer<QuickShapePointerPress> quickShapePointer,
      Consumer<QuickShapeUndoGesture.Event> quickShapeUndo,
      Consumer<StartPlacementPayload.Target> starts, Consumer<GeometryGizmoCapture.Event> geometryGizmos,
      Consumer<GeometryInputController.PointerPress> geometryPointers,
      Consumer<OperationPointDragEvent> operationPointDrags,
      Consumer<OperationPointCommandEvent> operationPointCommands) {
      drainPhysicalEvents(contextActive, new PhysicalInputSink() {
         public void accept(KeyboardInputSnapshot event) { keys.accept(event); }
         public void accept(ScrollInputSnapshot event) { scrolls.accept(event); }
         public void accept(SelectionPointerEvent event) { selectionPointer.accept(event); }
         public void accept(RemoteSelectionPointRequest event) { remotePoints.accept(event); }
         public void accept(PointerReleaseSnapshot event) { pointerReleases.accept(event); }
         public void accept(QuickShapeUndoGesture.Event event) { quickShapeUndo.accept(event); }
         public void accept(QuickShapePointerPress event) { quickShapePointer.accept(event); }
         public void accept(StartPlacementPayload.Target event) { starts.accept(event); }
         public void accept(GeometryGizmoCapture.Event event) { geometryGizmos.accept(event); }
         public void accept(GeometryInputController.PointerPress event) { geometryPointers.accept(event); }
         public void accept(OperationPointDragEvent event) { operationPointDrags.accept(event); }
         public void accept(OperationPointCommandEvent event) { operationPointCommands.accept(event); }
      });
   }

   void drainPhysicalEvents(BooleanSupplier contextActive, PhysicalInputSink sink) {
      this.physicalEvents.drain(contextActive, this::discardPhysicalEvents, sink);
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
