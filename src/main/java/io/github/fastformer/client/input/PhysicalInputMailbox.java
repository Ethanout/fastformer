package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientTickMailbox;
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
   }
   private final ClientTickMailbox<PhysicalEvent> events =
      new ClientTickMailbox<>(this::releaseQueuedEvent);
   private int pendingRemotePoints;

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

   void drain(BooleanSupplier contextActive, Runnable contextLost,
      Consumer<KeyboardInputSnapshot> keys, Consumer<ScrollInputSnapshot> scrolls,
      Consumer<SelectionPointerEvent> selectionPointer, Consumer<RemoteSelectionPointRequest> remotePoints,
      Consumer<PointerReleaseSnapshot> pointerReleases) {
      this.events.drain(event -> {
         releaseQueuedEvent(event);
         if (!contextActive.getAsBoolean()) {
            contextLost.run();
            return;
         }
         switch (event) {
            case PhysicalEvent.Key key -> keys.accept(key.value());
            case PhysicalEvent.Scroll scroll -> scrolls.accept(scroll.value());
            case PhysicalEvent.SelectionPointer click -> selectionPointer.accept(click.value());
            case PhysicalEvent.RemotePoint point -> remotePoints.accept(point.value());
            case PhysicalEvent.PointerRelease release -> pointerReleases.accept(release.value());
         }
      });
   }
}
