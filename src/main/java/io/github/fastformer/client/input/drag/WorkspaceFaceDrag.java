package io.github.fastformer.client.input.drag;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import net.minecraft.world.phys.Vec3;

/** Immutable state for push/pull gestures on a client workspace face. */
public record WorkspaceFaceDrag(
   ClientSelectionPart baseline,
   int axis,
   boolean positive,
   DragAxisFrame frame,
   Vec3 normal,
   int sentSteps,
   int mouseButton,
   DeferredDragClick deferredClick,
   OperationGeometry.RayHit hit,
   ClientOperationWorkspace.EditToken editToken
) {
   public WorkspaceFaceDrag withSentSteps(int value) {
      return new WorkspaceFaceDrag(
         baseline, axis, positive, frame, normal, value, mouseButton, deferredClick, hit, editToken
      );
   }

   public WorkspaceFaceDrag withDeferredClick(DeferredDragClick value) {
      return new WorkspaceFaceDrag(
         baseline, axis, positive, frame, normal, sentSteps, mouseButton, value, hit, editToken
      );
   }
}
