package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientTickMailbox;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns queued physical input and releases pending requests on dispatch or invalidation. */
final class PhysicalInputMailbox {
   private sealed interface PhysicalEvent {
      record Key(KeyboardInputSnapshot value) implements PhysicalEvent { }
      record Scroll(ScrollInputSnapshot value) implements PhysicalEvent { }
      record SelectionPointer(SelectionPointerEvent value) implements PhysicalEvent { }
      record RemotePoint(RemoteSelectionPointRequest value) implements PhysicalEvent { }
      record PointerRelease(PointerReleaseSnapshot value) implements PhysicalEvent { }
      record QuickShapeUndo(QuickShapeUndoGesture.Event value) implements PhysicalEvent { }
      record QuickShapePointer(QuickShapePointerPress value) implements PhysicalEvent { }
      record StartPlacement(StartPlacementPayload.Target value) implements PhysicalEvent { }
      record GeometryGizmo(GeometryGizmoCapture.Event value) implements PhysicalEvent { }
      record GeometryPointer(GeometryInputController.PointerPress value) implements PhysicalEvent { }
      record OperationPointDrag(OperationPointDragEvent value) implements PhysicalEvent { }
      record OperationPointCommand(OperationPointCommandEvent value) implements PhysicalEvent { }
   }
   private final ClientTickMailbox<PhysicalEvent> events =
      new ClientTickMailbox<>(this::releaseQueuedEvent);
   private int pendingRemotePoints;
   private int pendingStarts;

   boolean hasPendingStarts() {
      return pendingStarts > 0;
   }

   boolean hasPendingRemotePoints() {
      return pendingRemotePoints > 0;
   }

   boolean hasUnfinishedEvents() {
      return events.hasUnfinishedEvents();
   }

   void invalidate() {
      events.invalidate();
   }

   private void releaseQueuedEvent(PhysicalEvent event) {
      if (event instanceof PhysicalEvent.RemotePoint) pendingRemotePoints--;
      if (event instanceof PhysicalEvent.StartPlacement) pendingStarts--;
   }

   void postKeyboard(KeyboardInputSnapshot event) {
      this.events.post(new PhysicalEvent.Key(java.util.Objects.requireNonNull(event)));
   }

   void postScroll(ScrollInputSnapshot event) {
      this.events.post(new PhysicalEvent.Scroll(java.util.Objects.requireNonNull(event)));
   }

   void postSelectionPointer(SelectionPointerEvent event) {
      this.events.post(new PhysicalEvent.SelectionPointer(java.util.Objects.requireNonNull(event)));
   }

   void postRemoteSelectionPoint(RemoteSelectionPointRequest request) {
      this.events.post(new PhysicalEvent.RemotePoint(java.util.Objects.requireNonNull(request)));
      this.pendingRemotePoints++;
   }

   void postPointerRelease(PointerReleaseSnapshot event) {
      this.events.post(new PhysicalEvent.PointerRelease(java.util.Objects.requireNonNull(event)));
   }

   void postQuickShapeUndo(QuickShapeUndoGesture.Event event) {
      this.events.post(new PhysicalEvent.QuickShapeUndo(java.util.Objects.requireNonNull(event)));
   }

   void postQuickShapePointer(QuickShapePointerPress event) {
      this.events.post(new PhysicalEvent.QuickShapePointer(java.util.Objects.requireNonNull(event)));
   }

   void postStartPlacement(StartPlacementPayload.Target target) {
      this.events.post(new PhysicalEvent.StartPlacement(java.util.Objects.requireNonNull(target)));
      this.pendingStarts++;
   }

   void postGeometryGizmo(GeometryGizmoCapture.Event event) {
      this.events.post(new PhysicalEvent.GeometryGizmo(java.util.Objects.requireNonNull(event)));
   }

   void postGeometryPointer(GeometryInputController.PointerPress event) {
      this.events.post(new PhysicalEvent.GeometryPointer(java.util.Objects.requireNonNull(event)));
   }

   void postOperationPointDrag(OperationPointDragEvent event) {
      this.events.post(new PhysicalEvent.OperationPointDrag(java.util.Objects.requireNonNull(event)));
   }

   void postOperationPointCommand(OperationPointCommandEvent event) {
      this.events.post(new PhysicalEvent.OperationPointCommand(java.util.Objects.requireNonNull(event)));
   }

   void drain(BooleanSupplier contextActive, Runnable contextLost, PhysicalInputSink sink) {
      this.events.drain(event -> {
         releaseQueuedEvent(event);
         if (!contextActive.getAsBoolean()) {
            contextLost.run();
            return;
         }
         switch (event) {
            case PhysicalEvent.Key value -> sink.accept(value.value());
            case PhysicalEvent.Scroll value -> sink.accept(value.value());
            case PhysicalEvent.SelectionPointer value -> sink.accept(value.value());
            case PhysicalEvent.RemotePoint value -> sink.accept(value.value());
            case PhysicalEvent.PointerRelease value -> sink.accept(value.value());
            case PhysicalEvent.QuickShapeUndo value -> sink.accept(value.value());
            case PhysicalEvent.QuickShapePointer value -> sink.accept(value.value());
            case PhysicalEvent.StartPlacement value -> sink.accept(value.value());
            case PhysicalEvent.GeometryGizmo value -> sink.accept(value.value());
            case PhysicalEvent.GeometryPointer value -> sink.accept(value.value());
            case PhysicalEvent.OperationPointDrag value -> sink.accept(value.value());
            case PhysicalEvent.OperationPointCommand value -> sink.accept(value.value());
         }
      });
   }
}
