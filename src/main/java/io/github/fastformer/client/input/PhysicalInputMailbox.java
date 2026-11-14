package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.input.OperationPointCommandEvent;
import io.github.fastformer.client.operation.input.OperationPointDragEvent;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import io.github.fastformer.client.quickshape.input.QuickShapePointerPress;
import io.github.fastformer.client.quickshape.input.QuickShapeUndoGesture;
import io.github.fastformer.core.async.TickMailbox;
import io.github.fastformer.protocol.c2s.placement.StartPlacementPayload;
import java.util.function.BooleanSupplier;

/** Owns queued physical input and releases pending requests on dispatch or invalidation. */
final class PhysicalInputMailbox {
   private sealed interface PhysicalEvent {
      record Key(KeyboardInputSnapshot value) implements PhysicalEvent { }
      record Scroll(ScrollInputSnapshot value, BooleanSupplier valid) implements PhysicalEvent { }
      record SelectionPointer(SelectionPointerEvent value) implements PhysicalEvent { }
      record RemotePoint(RemoteSelectionPointRequest value) implements PhysicalEvent { }
      record PointerRelease(PointerReleaseSnapshot value) implements PhysicalEvent { }
      record QuickShapeUndo(QuickShapeUndoGesture.Event value, BooleanSupplier valid) implements PhysicalEvent { }
      record QuickShapePointer(QuickShapePointerPress value, BooleanSupplier valid) implements PhysicalEvent { }
      record StartPlacement(StartPlacementPayload.Target value) implements PhysicalEvent { }
      record GeometryGizmo(GeometryGizmoCapture.Event value) implements PhysicalEvent { }
      record GeometryPointer(GeometryInputController.PointerPress value) implements PhysicalEvent { }
      record OperationPointDrag(OperationPointDragEvent value) implements PhysicalEvent { }
      record OperationPointCommand(OperationPointCommandEvent value) implements PhysicalEvent { }
   }
   private final TickMailbox<PhysicalEvent> events =
      new TickMailbox<>(this::releaseQueuedEvent);
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
      postScroll(event, () -> true);
   }

   void postScroll(ScrollInputSnapshot event, BooleanSupplier valid) {
      this.events.post(new PhysicalEvent.Scroll(java.util.Objects.requireNonNull(event), valid));
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
      postQuickShapeUndo(event, () -> true);
   }

   void postQuickShapeUndo(QuickShapeUndoGesture.Event event, BooleanSupplier valid) {
      this.events.post(new PhysicalEvent.QuickShapeUndo(java.util.Objects.requireNonNull(event), valid));
   }

   void postQuickShapePointer(QuickShapePointerPress event) {
      postQuickShapePointer(event, () -> true);
   }

   void postQuickShapePointer(QuickShapePointerPress event, BooleanSupplier valid) {
      this.events.post(new PhysicalEvent.QuickShapePointer(java.util.Objects.requireNonNull(event), valid));
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
            case PhysicalEvent.Scroll value -> { if (value.valid().getAsBoolean()) sink.accept(value.value()); }
            case PhysicalEvent.SelectionPointer value -> sink.accept(value.value());
            case PhysicalEvent.RemotePoint value -> sink.accept(value.value());
            case PhysicalEvent.PointerRelease value -> sink.accept(value.value());
            case PhysicalEvent.QuickShapeUndo value -> { if (value.valid().getAsBoolean()) sink.accept(value.value()); }
            case PhysicalEvent.QuickShapePointer value -> { if (value.valid().getAsBoolean()) sink.accept(value.value()); }
            case PhysicalEvent.StartPlacement value -> sink.accept(value.value());
            case PhysicalEvent.GeometryGizmo value -> sink.accept(value.value());
            case PhysicalEvent.GeometryPointer value -> sink.accept(value.value());
            case PhysicalEvent.OperationPointDrag value -> sink.accept(value.value());
            case PhysicalEvent.OperationPointCommand value -> sink.accept(value.value());
         }
      });
   }
}
