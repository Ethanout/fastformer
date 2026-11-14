package io.github.fastformer.client.input;

import io.github.fastformer.client.interaction.binding.input.PhysicalButtons;
import io.github.fastformer.client.input.state.EntryPressGuard;

import io.github.fastformer.client.input.mouse.PointerButtonCapture;
import io.github.fastformer.client.interaction.binding.tool.QuickShapeBindingInputState;
import io.github.fastformer.client.interaction.binding.tool.GeometryBindingInputState;

import io.github.fastformer.client.interaction.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.client.input.gesture.PathCloseGesture;
import io.github.fastformer.client.input.gesture.ShortPressTracker;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ClientSemanticEvent;
import io.github.fastformer.client.input.state.ModifierGestureState;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.operation.input.OperationPointCommandEvent;
import io.github.fastformer.client.operation.input.OperationPointCommandPress;
import io.github.fastformer.client.operation.input.OperationPointDragCapture;
import io.github.fastformer.client.operation.input.OperationPointDragEvent;
import io.github.fastformer.client.operation.input.OperationPointDragPress;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointPress;
import io.github.fastformer.client.operation.input.SelectionPointerCapture;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import io.github.fastformer.client.operation.input.SelectionPointerEvent.RemoteSnapshot;
import io.github.fastformer.client.interaction.binding.tool.SelectionBindingInputState;
import io.github.fastformer.client.operation.input.SelectionPointerPress;
import io.github.fastformer.client.quickshape.input.QuickShapePointerPress;
import io.github.fastformer.client.quickshape.input.QuickShapeUndoGesture;
import io.github.fastformer.core.async.TickMailbox;
import io.github.fastformer.protocol.c2s.placement.StartPlacementPayload;
import io.github.fastformer.common.selection.OperationSelectionMode;
import net.minecraft.core.BlockPos;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Transient input state owned by one player environment, never persisted with a draft. */
public final class ClientInputSession {
   private final io.github.fastformer.client.feedback.InteractionFeedback feedback =
      new io.github.fastformer.client.feedback.InteractionFeedback();
   public io.github.fastformer.client.feedback.InteractionFeedback feedback() { return feedback; }
   private final io.github.fastformer.client.interaction.pointer.PointerResolver pointer =
      new io.github.fastformer.client.interaction.pointer.PointerResolver();
   public io.github.fastformer.client.interaction.pointer.PointerResolver pointer() { return pointer; }
   private io.github.fastformer.client.geometry.pointer.GeometryPointer geometryPointer;
   public io.github.fastformer.client.geometry.pointer.GeometryPointer geometryPointer(
      java.util.function.Supplier<io.github.fastformer.client.geometry.pointer.GeometryPointer> factory) {
      if (geometryPointer == null) geometryPointer = java.util.Objects.requireNonNull(factory.get(), "geometryPointer");
      return geometryPointer;
   }
   public void clearGeometryPointer() { if (geometryPointer != null) geometryPointer.clear(); }
   public void invalidateGeometryPlan() { if (geometryPointer != null) geometryPointer.invalidatePlan(); }
   private io.github.fastformer.client.quickshape.pointer.QuickShapePointer quickShapePointer;
   public io.github.fastformer.client.quickshape.pointer.QuickShapePointer quickShapePointer(
      java.util.function.Supplier<io.github.fastformer.client.quickshape.pointer.QuickShapePointer> factory) {
      if (quickShapePointer == null) quickShapePointer = java.util.Objects.requireNonNull(factory.get(), "quickShapePointer");
      return quickShapePointer;
   }
   public io.github.fastformer.client.mode.ClientModeView modeView(
      io.github.fastformer.client.mode.ModeObservation observation
   ) {
      return io.github.fastformer.client.mode.ClientModeView.capture(
         io.github.fastformer.client.mode.ClientModes.registry(), observation, routing.state(), ownsQuickShapeStart());
   }
   public boolean canCreateSelection() {
      return routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION) == ClientInputStateMachine.Dispatch.OPERATION;
   }
   private io.github.fastformer.client.interaction.selectiongesture.SelectionTransformGestures selectionGestures;
   public io.github.fastformer.client.interaction.selectiongesture.SelectionTransformGestures selectionGestures() {
      if (selectionGestures == null) throw new IllegalStateException("Selection gestures require an operation context");
      return selectionGestures;
   }
   public io.github.fastformer.client.interaction.selectiongesture.SelectionGestureSnapshot selectionGestureSnapshot() {
      return selectionGestures == null
         ? io.github.fastformer.client.interaction.selectiongesture.SelectionGestureSnapshot.EMPTY
         : selectionGestures.snapshot();
   }
   public void bindSelectionGestures(io.github.fastformer.client.interaction.selectiongesture.SelectionTransformGestures gestures) {
      if (selectionGestures != null) throw new IllegalStateException("Selection gesture owner is already bound");
      selectionGestures = java.util.Objects.requireNonNull(gestures, "gestures");
   }
   public io.github.fastformer.client.interaction.selectiongesture.SelectionGesturePointer selectionGesturePointer() {
      return new io.github.fastformer.client.interaction.selectiongesture.SelectionGesturePointer() {
         private PointerGestureState.Kind pointerKind(Kind kind) {
            return kind == Kind.FACE ? PointerGestureState.Kind.WORKSPACE_FACE : PointerGestureState.Kind.WORKSPACE_GIZMO;
         }
         public long begin(Kind kind) { return pointerGesture.begin(pointerKind(kind)); }
         public boolean owns(long token, Kind kind) { return pointerGesture.owns(token, pointerKind(kind)); }
         public void finish(long token) { pointerGesture.finish(token); }
         public void consumeModifier() { if (modifier.held()) modifier.consume(); }
      };
   }
   private final ClientSmartFillSession smartFill = new ClientSmartFillSession();
   public ClientSmartFillSession smartFill() { return smartFill; }
   final IdleEntryGesture idleEntry = new IdleEntryGesture();
   final BindingInputController bindings = new BindingInputController();
   public ClientInputStateMachine.State inputState() { return routing.state(); }
   public boolean routesToVanilla(ClientInputStateMachine.InputKind kind) { return routing.routesToVanilla(kind); }
   public io.github.fastformer.client.interaction.binding.action.BindingAction.Result dispatchToolBinding(
      net.minecraft.client.Minecraft minecraft, String name, String gesture,
      io.github.fastformer.client.interaction.binding.context.BindingContext context, long now) {
      return bindings.onToolSignalResult(minecraft, this, name, gesture, context, now);
   }
   final EntryPressGuard entryPressGuard = new EntryPressGuard();
   final EntryPressGuard cuboidPointPressGuard = new EntryPressGuard();
   int idleEntryButton = -1;
   int idleEntrySelectionButton = -1;
   boolean entryConsumedAlt;
   IdleSelectionEntryController.Pending idleSelectionEntry;
   final io.github.fastformer.client.quickshape.QuickShapeSubmissionIntent quickShapeSubmission =
      new io.github.fastformer.client.quickshape.QuickShapeSubmissionIntent();
   private final TickMailbox<ClientSemanticEvent.SubmissionCompleted> submissionEvents =
      new TickMailbox<>(event -> { });
   private final PhysicalInputMailbox physicalEvents = new PhysicalInputMailbox();
   final ShortPressTracker undoPress = new ShortPressTracker();
   final QuickShapeUndoGesture quickShapeUndo = new QuickShapeUndoGesture();
   final QuickShapeBindingInputState shapeInput = new QuickShapeBindingInputState();
   final GeometryBindingInputState geometryInput = new GeometryBindingInputState();
   final SelectionBindingInputState selectionInput = new SelectionBindingInputState();
   final ShortPressTracker selectionUndo = new ShortPressTracker();
   int selectionUndoButton = -1;
   RemoteSnapshot selectionUndoPress;
   final ShortPressTracker geometryFallbackUndo = new ShortPressTracker();
   int geometryUndoButton = -1;
   final SelectionPointerCapture selectionPointer = new SelectionPointerCapture();
   final OperationPointDragCapture operationPointPointer = new OperationPointDragCapture();
   final GeometryGizmoCapture geometryGizmoCapture = new GeometryGizmoCapture();
   final PhysicalButtons idleButtons = new PhysicalButtons();
   private final java.util.Set<Integer> quickShapeButtons = new java.util.HashSet<>();
   private final java.util.Set<Integer> geometryPointerButtons = new java.util.HashSet<>();

   final PointerGestureState pointerGesture = new PointerGestureState();
   final ClientInputStateMachine routing;

   public ClientInputSession() {
      routing = new ClientInputStateMachine();
   }
   final ModifierGestureState modifier = new ModifierGestureState();
   long clickGestureToken;
   OperationDrag operationDrag;
   OperationPointDrag operationPointDrag;
   final PointerButtonCapture operationClick = new PointerButtonCapture();
   GeometryGizmoDrag geometryGizmoDrag;
   OperationTransformCapture operationTransformCapture;
   record OperationTransformCapture(long gestureId, long revision,
      io.github.fastformer.protocol.callback.OperationCallbackScope scope) { }
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
      return button >= 0 && this.quickShapeButtons.add(button);
   }

   boolean releaseQuickShapeButton(int button) {
      return this.quickShapeButtons.remove(button);
   }

   boolean quickShapeButtonCaptured(int button) {
      return this.quickShapeButtons.contains(button);
   }

   boolean hasQuickShapeButtons() {
      return !this.quickShapeButtons.isEmpty();
   }

   public boolean blocksDraftLoad() {
      boolean activeOperation = io.github.fastformer.client.mode.ClientModes.registry().policy(routing.state()).blocksDraftLoad();
      return activeOperation
         || this.physicalEvents.hasUnfinishedEvents() || this.submissionEvents.hasUnfinishedEvents()
         || this.selectionPointer.active() || this.selectionInput.active()
         || this.pointerGesture.kind() != PointerGestureState.Kind.NONE
         || this.clickGestureToken != 0L || this.undoPressCaptured || this.quickShapeUndo.captured()
         || hasQuickShapeButtons() || this.geometryGizmoCapture.captured() || hasGeometryPointerButtons() || this.modifier.held();
   }

   public void reset() {
      feedback.clear();
      pointer.invalidate();
      clearGeometryPointer();
      smartFill.clear();
      clearIdleEntry();
      entryConsumedAlt = false;
      cancelPointerState();
      this.quickShapeSubmission.cancel();
      this.submissionEvents.invalidate();
      this.physicalEvents.invalidate();
      this.routing.reset();
      this.modifier.reset();
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
      clearIdleEntry();
      clearPointerCaptures();
      this.physicalEvents.invalidate();
      this.undoPress.cancel();
      this.undoPressCaptured = false;
   }

   void clearEntryInput() {
      idleButtons.clear();
      idleEntry.clear();
      entryPressGuard.clear();
      cuboidPointPressGuard.clear();
   }

   private void clearIdleEntry() {
      bindings.reset();
      clearEntryInput();
      idleSelectionEntry = null;
      idleEntryButton = -1;
      idleEntrySelectionButton = -1;
   }

   private void clearPointerCaptures() {
      cancelQueuedGeometryInput();
      this.shapeInput.clear();
      this.quickShapeButtons.clear();
      this.quickShapeUndo.cancel();
      this.selectionPointer.clear();
      this.operationPointPointer.clear();
      this.selectionInput.clear();
      this.selectionUndo.cancel();
      this.selectionUndoButton = -1;
      this.selectionUndoPress = null;
   }

   void cancelBoundPointerInput() {
      this.shapeInput.clear();
      this.quickShapeButtons.clear();
      this.quickShapeUndo.cancel();
      cancelQueuedGeometryInput();
      cancelQueuedSelectionInput();
   }

   private void cancelQueuedSelectionInput() {
      this.selectionInput.clear();
      this.selectionUndo.cancel();
      this.selectionUndoButton = -1;
      this.selectionUndoPress = null;
      this.selectionPointer.clear();
      this.operationPointPointer.clear();
      if (this.selectionGestures != null) this.selectionGestures.cancel();
      if (this.operationDrag != null) this.pointerGesture.finish(this.operationDrag.captureToken());
      if (this.operationPointDrag != null) this.pointerGesture.finish(this.operationPointDrag.captureToken());
      if (this.operationTransformCapture != null && this.geometryGizmoDrag != null) {
         this.pointerGesture.finish(this.geometryGizmoDrag.captureToken());
         this.geometryGizmoDrag = null;
      }
      this.operationDrag = null;
      this.operationPointDrag = null;
      this.operationTransformCapture = null;
      this.operationClick.clear();
      this.clickGestureToken = 0L;
   }

   /** A physical release always cleans up, even if its resource action was disabled. */
   void finishGeometryPhysicalRelease(int button) {
      releaseGeometryPointerButton(button);
      if (this.geometryGizmoCapture.ownsPhysicalButton(button)) cancelQueuedGeometryInput();
      if (this.geometryUndoButton == button) {
         this.geometryUndoButton = -1;
         this.geometryFallbackUndo.cancel();
      }
   }

   void finishShapePhysicalRelease(int button) {
      releaseQuickShapeButton(button);
      var cancelled = this.quickShapeUndo.abandon(button);
      if (cancelled != null) postShapeUndo(cancelled);
   }

   void postShapePointer(QuickShapePointerPress event) {
      long generation = this.shapeInput.generation();
      this.physicalEvents.postQuickShapePointer(event, () -> this.shapeInput.accepts(generation));
   }

   void postShapeUndo(QuickShapeUndoGesture.Event event) {
      long generation = this.shapeInput.generation();
      this.physicalEvents.postQuickShapeUndo(event, () -> this.shapeInput.accepts(generation));
   }

   void postShapeScroll(ScrollInputSnapshot event) {
      long generation = this.shapeInput.generation();
      this.physicalEvents.postScroll(event, () -> this.shapeInput.accepts(generation));
   }

   /** Releases all pointer-owned state without touching the active draft. */
   void cancelPointerState() {
      if (selectionGestures != null) selectionGestures.cancel();
      clearPointerCaptures();
      this.pointerGesture.cancel();
      this.operationDrag = null;
      this.operationPointDrag = null;
      this.operationClick.clear();
      this.geometryGizmoDrag = null;
      this.operationTransformCapture = null;
      this.undoPress.cancel();
      this.undoPressCaptured = false;
      this.clickGestureToken = 0L;
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
      long generation = this.selectionInput.generation();
      this.physicalEvents.postSelectionPointer(event, () -> this.selectionInput.accepts(generation));
   }

   void postRemoteSelectionPoint(RemoteSelectionPointRequest request) {
      long generation = this.selectionInput.generation();
      this.physicalEvents.postRemoteSelectionPoint(request, () -> this.selectionInput.accepts(generation));
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
      return captureSelectionPress(snapshot, snapshot.button(), 200);
   }

   SelectionPointerEvent.CreatePress captureSelectionPress(SelectionDraftPress snapshot, int physicalButton, int durationMillis) {
      if (snapshot.mode() == OperationSelectionMode.CUBOID
         && !acceptCuboidPointPress(snapshot.button(), physicalButton, snapshot.point(), snapshot.occurredAtNanos(), durationMillis)) return null;
      cancelSupersededSelectionPress();
      var press = this.selectionPointer.press(snapshot, physicalButton);
      postSelectionPointer(press);
      return press;
   }

   boolean acceptCuboidPointPress(int button, BlockPos point, long now) {
      return acceptCuboidPointPress(button, button, point, now, 200);
   }
   boolean acceptCuboidPointPress(int logicalButton, int physicalButton, BlockPos point, long now, int durationMillis) {
      if (logicalButton != MouseButtonInputSemantics.RIGHT_BUTTON || point == null) return true;
      if (cuboidPointPressGuard.capture(MouseButtonInputSemantics.PRESS, physicalButton, now)) return false;
      cuboidPointPressGuard.arm(now, physicalButton, durationMillis);
      return true;
   }

   SelectionPointerEvent.PointPress captureSelectionPress(SelectionPointPress snapshot) {
      return captureSelectionPress(snapshot, snapshot.button());
   }
   SelectionPointerEvent.PointPress captureSelectionPress(SelectionPointPress snapshot, int physicalButton) {
      cancelSupersededSelectionPress();
      var press = this.selectionPointer.press(snapshot, physicalButton);
      postSelectionPointer(press);
      return press;
   }

   void captureSelectionPress(RemoteSnapshot snapshot) {
      cancelSupersededSelectionPress();
      postSelectionPointer(this.selectionPointer.press(snapshot));
   }

   private void cancelSupersededSelectionPress() {
      var superseded = this.selectionPointer.supersededPress();
      if (superseded != null) postSelectionPointer(superseded);
   }

   /** Called only after a resource action has accepted a replacement press. */
   void supersedeSelectionCaptures() {
      cancelSupersededSelectionPress();
      var point = this.operationPointPointer.superseded();
      if (point != null) postOperationPointDrag(point);
      this.selectionUndo.cancel();
      this.selectionUndoButton = -1;
      this.selectionUndoPress = null;
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
      long generation = this.geometryInput.generation();
      this.physicalEvents.postGeometryGizmo(event, () -> this.geometryInput.accepts(generation));
   }

   private void cancelQueuedGeometryInput() {
      this.geometryInput.clear();
      this.geometryPointerButtons.clear();
      this.geometryFallbackUndo.cancel();
      this.geometryUndoButton = -1;
      if (this.geometryGizmoDrag != null && this.geometryGizmoCapture.owns(this.geometryGizmoDrag.captureToken())) {
         long token = this.geometryGizmoDrag.captureToken();
         this.pointerGesture.finish(token);
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
      long generation = this.geometryInput.generation();
      this.physicalEvents.postGeometryPointer(event, () -> this.geometryInput.accepts(generation));
   }

   void postOperationPointDrag(OperationPointDragEvent event) {
      long generation = this.selectionInput.generation();
      this.physicalEvents.postOperationPointDrag(event, () -> this.selectionInput.accepts(generation));
   }

   void postOperationPointCommand(OperationPointCommandEvent event) {
      long generation = this.selectionInput.generation();
      this.physicalEvents.postOperationPointCommand(event, () -> this.selectionInput.accepts(generation));
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
      if (button >= 0) this.geometryPointerButtons.add(button);
   }

   boolean ownsGeometryPointerButton(int button) {
      return this.geometryPointerButtons.contains(button);
   }

   boolean hasGeometryPointerButtons() {
      return !this.geometryPointerButtons.isEmpty();
   }

   boolean releaseGeometryPointerButton(int button) {
      return this.geometryPointerButtons.remove(button);
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
