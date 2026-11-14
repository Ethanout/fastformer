package io.github.fastformer.client.interaction.selectiongesture;


/** Immutable presentation state; it grants no edit or cancellation authority. */
public record SelectionGestureSnapshot(WorkspaceFaceDrag face, WorkspaceGizmoDrag gizmo) {
   public static final SelectionGestureSnapshot EMPTY = new SelectionGestureSnapshot(null, null);
   public boolean active() { return face != null || gizmo != null; }
   public SelectionDragCapture capture() {
      return face != null ? face.capture() : gizmo == null ? null : gizmo.capture();
   }
}
