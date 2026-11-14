package io.github.fastformer.client.interaction.selectiongesture;

import io.github.fastformer.client.interaction.drag.DeferredDragClick;
import io.github.fastformer.client.interaction.drag.DragAxisFrame;
import net.minecraft.world.phys.Vec3;

/** Read-only face gesture state; the actual edit lease stays inside the capability. */
public record WorkspaceFaceDrag(int partId, int axis, boolean positive, DragAxisFrame frame, Vec3 normal,
   int sentSteps, SelectionDragCapture capture, DeferredDragClick deferredClick, SelectionFaceHit hit,
   boolean awaitingSource) {
   public int mouseButton() { return capture.mouseButton(); }
   WorkspaceFaceDrag withSentSteps(int value) {
      return new WorkspaceFaceDrag(partId, axis, positive, frame, normal, value, capture, deferredClick, hit, awaitingSource);
   }
   WorkspaceFaceDrag withDeferredClick(DeferredDragClick value) {
      return new WorkspaceFaceDrag(partId, axis, positive, frame, normal, sentSteps, capture, value, hit, awaitingSource);
   }
   WorkspaceFaceDrag sourceReady() {
      return new WorkspaceFaceDrag(partId, axis, positive, frame, normal, sentSteps, capture, deferredClick, hit, false);
   }
}
