package io.github.fastformer.client.interaction.selectiongesture;

/** Capability-private ownership state; presentation snapshots never expose these edit ports. */
sealed interface SelectionDrag {
   SelectionDragCapture capture();
   SelectionGestureEdits.Edit edit();
   record Face(WorkspaceFaceDrag view, SelectionGestureEdits.FaceEdit edit) implements SelectionDrag {
      public SelectionDragCapture capture() { return view.capture(); }
   }
   record Gizmo(WorkspaceGizmoDrag view, SelectionGestureEdits.GizmoEdit edit) implements SelectionDrag {
      public SelectionDragCapture capture() { return view.capture(); }
   }
}
