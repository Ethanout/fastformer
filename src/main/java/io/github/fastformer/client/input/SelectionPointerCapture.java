package io.github.fastformer.client.input;

import io.github.fastformer.client.interaction.SelectionDragCapture;

/** Pairs physical callbacks without advancing the input state machine before dispatch. */
final class SelectionPointerCapture {
   private long sequence;
   private long captured;
   private int button = -1;
   private Dispatch dispatched;

   record Dispatch(long identity, SelectionDragCapture drag, long routingToken) { }

   boolean active() {
      return this.captured != 0L || this.dispatched != null;
   }

   boolean owns(SelectionDragCapture drag) {
      return drag != null && this.dispatched != null && this.dispatched.drag() == drag;
   }

   SelectionPointerEvent.Cancel supersededPress() {
      return this.captured == 0L ? null : new SelectionPointerEvent.Cancel(this.captured);
   }

   SelectionPointerEvent.Press press(SelectionPointerPress snapshot, boolean alt) {
      return new SelectionPointerEvent.Press(capture(snapshot.button()), snapshot, alt);
   }

   SelectionPointerEvent.CreatePress press(SelectionDraftPress snapshot) {
      return new SelectionPointerEvent.CreatePress(capture(snapshot.button()), snapshot);
   }

   SelectionPointerEvent.PointPress press(SelectionPointPress snapshot) {
      return new SelectionPointerEvent.PointPress(capture(snapshot.button()), snapshot);
   }

   private long capture(int button) {
      // As with drag routing, the newest press owns the single pointer capture.
      this.captured = ++this.sequence;
      this.button = button;
      return this.captured;
   }

   SelectionPointerEvent.Release release(int releasedButton, long occurredAtNanos) {
      if (this.captured == 0L || this.button != releasedButton) return null;
      var result = new SelectionPointerEvent.Release(this.captured, releasedButton, occurredAtNanos);
      this.captured = 0L;
      this.button = -1;
      return result;
   }

   void dispatched(long identity, SelectionDragCapture drag, long routingToken) {
      this.dispatched = new Dispatch(identity, drag, routingToken);
   }

   Dispatch take(long identity) {
      if (this.dispatched == null || this.dispatched.identity() != identity) return null;
      Dispatch result = this.dispatched;
      this.dispatched = null;
      return result;
   }

   void clear() {
      this.captured = 0L;
      this.button = -1;
      this.dispatched = null;
   }
}
