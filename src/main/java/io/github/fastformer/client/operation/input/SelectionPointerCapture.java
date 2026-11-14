package io.github.fastformer.client.operation.input;

import io.github.fastformer.client.interaction.selectiongesture.SelectionDragCapture;
import io.github.fastformer.client.operation.input.SelectionPointerEvent.RemoteSnapshot;

/** Pairs physical callbacks without advancing the input state machine before dispatch. */
public final class SelectionPointerCapture {
   private long sequence;
   private long captured;
   private int button = -1;
   private Dispatch dispatched;

   public record Dispatch(long identity, SelectionDragCapture drag, long routingToken,
      RemoteSnapshot remote, long pointerToken) { }

   public boolean active() {
      return this.captured != 0L || this.dispatched != null;
   }

   public boolean owns(SelectionDragCapture drag) {
      return drag != null && this.dispatched != null && this.dispatched.drag() == drag;
   }

   public SelectionPointerEvent.Cancel supersededPress() {
      return this.captured == 0L ? null : new SelectionPointerEvent.Cancel(this.captured);
   }

   public SelectionPointerEvent.Press press(SelectionPointerPress snapshot, boolean alt) {
      return new SelectionPointerEvent.Press(capture(snapshot.button()), snapshot, alt);
   }

   public SelectionPointerEvent.CreatePress press(SelectionDraftPress snapshot) {
      return press(snapshot, snapshot.button());
   }
   public SelectionPointerEvent.CreatePress press(SelectionDraftPress snapshot, int physicalButton) {
      return new SelectionPointerEvent.CreatePress(capture(physicalButton), snapshot);
   }

   public SelectionPointerEvent.PointPress press(SelectionPointPress snapshot) {
      return press(snapshot, snapshot.button());
   }
   public SelectionPointerEvent.PointPress press(SelectionPointPress snapshot, int physicalButton) {
      return new SelectionPointerEvent.PointPress(capture(physicalButton), snapshot);
   }
   public SelectionPointerEvent.RemotePress press(
      RemoteSnapshot snapshot) {
      return new SelectionPointerEvent.RemotePress(capture(snapshot.button()), snapshot);
   }

   private long capture(int button) {
      // As with drag routing, the newest press owns the single pointer capture.
      this.captured = ++this.sequence;
      this.button = button;
      return this.captured;
   }

   public SelectionPointerEvent.Release release(int releasedButton, long occurredAtNanos) {
      if (this.captured == 0L || this.button != releasedButton) return null;
      var result = new SelectionPointerEvent.Release(this.captured, releasedButton, occurredAtNanos);
      this.captured = 0L;
      this.button = -1;
      return result;
   }

   public void dispatched(long identity, SelectionDragCapture drag, long routingToken) {
      this.dispatched = new Dispatch(identity, drag, routingToken, null, 0L);
   }

   public void dispatchedRemote(long identity, long routingToken,
      RemoteSnapshot remote, long pointerToken) {
      this.dispatched = new Dispatch(identity, null, routingToken, remote, pointerToken);
   }

   public Dispatch take(long identity) {
      if (this.dispatched == null || this.dispatched.identity() != identity) return null;
      Dispatch result = this.dispatched;
      this.dispatched = null;
      return result;
   }

   public void clear() {
      this.captured = 0L;
      this.button = -1;
      this.dispatched = null;
   }
}
