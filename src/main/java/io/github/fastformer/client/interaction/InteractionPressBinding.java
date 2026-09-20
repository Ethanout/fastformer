package io.github.fastformer.client.interaction;

import io.github.fastformer.client.input.OperationInteractionIntent;
import java.util.Optional;

public enum InteractionPressBinding {
   SELECT, FRAME, GIZMO;

   public Optional<Action> resolve(OperationInteractionIntent target) {
      if ((this == SELECT || this == FRAME) && target instanceof OperationInteractionIntent.Part part) {
         return Optional.of(new SelectPart(part.partId()));
      }
      if (this == FRAME && target instanceof OperationInteractionIntent.Face face) {
         return Optional.of(new SelectOrDragFace(face));
      }
      if (this == GIZMO && target instanceof OperationInteractionIntent.Gizmo gizmo) {
         return Optional.of(new DragGizmo(gizmo));
      }
      return Optional.empty();
   }

   public sealed interface Action permits SelectPart, SelectOrDragFace, DragGizmo { }
   public record SelectPart(int partId) implements Action { }
   public record SelectOrDragFace(OperationInteractionIntent.Face target) implements Action { }
   public record DragGizmo(OperationInteractionIntent.Gizmo target) implements Action { }
}
