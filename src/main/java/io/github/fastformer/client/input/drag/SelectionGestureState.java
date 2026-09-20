package io.github.fastformer.client.input.drag;

import io.github.fastformer.client.interaction.SelectionDragCapture;
import java.util.Objects;

/** One selection gesture per session. Late updates cannot replace a newer gesture. */
public final class SelectionGestureState {
   private SelectionDrag current;

   public boolean active() {
      return this.current != null;
   }

   public SelectionDragCapture capture() {
      return this.current == null ? null : this.current.capture();
   }

   public SelectionDrag capturedBy(SelectionDragCapture capture) {
      return capture != null && this.current != null && this.current.capture() == capture ? this.current : null;
   }

   public WorkspaceFaceDrag face() {
      return this.current instanceof WorkspaceFaceDrag face ? face : null;
   }

   public WorkspaceGizmoDrag gizmo() {
      return this.current instanceof WorkspaceGizmoDrag gizmo ? gizmo : null;
   }

   public void begin(SelectionDrag drag) {
      Objects.requireNonNull(drag, "drag");
      if (this.current != null) throw new IllegalStateException("A selection gesture is already active");
      this.current = drag;
   }

   public boolean update(SelectionDrag expected, SelectionDrag next) {
      Objects.requireNonNull(next, "next");
      if (expected == null || this.current != expected
         || expected.getClass() != next.getClass()
         || expected.capture() != next.capture() || expected.editToken() != next.editToken()) return false;
      this.current = next;
      return true;
   }

   public void clear(SelectionDrag expected) {
      if (this.current == expected) this.current = null;
   }

   public void clear() {
      this.current = null;
   }
}
