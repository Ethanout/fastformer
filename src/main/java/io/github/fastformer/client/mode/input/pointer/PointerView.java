package io.github.fastformer.client.mode.input.pointer;

/** Facts already sampled by the input boundary; modes never query a live renderer. */
public sealed interface PointerView {
   record Left(
      boolean yieldToVanilla, boolean workspacePointerTarget, boolean workspaceActive,
      boolean operationGizmoTarget, boolean operationPointTarget, boolean operationUndo,
      boolean operationAlt, boolean operationAdjust, boolean cuboidWorldTarget, boolean cuboidFaceTarget,
      boolean operationCaptured, boolean operationDragCaptured, boolean geometryCaptured,
      boolean geometryGizmoDragging, boolean geometryGizmoTarget, boolean geometryInteractionTarget,
      boolean undoAvailable
   ) implements PointerView {}

   record Right(
      boolean vanillaOwnsPointer, boolean geometryCaptured, boolean workspacePointerTarget,
      boolean workspaceActive, boolean selectionConfirmed, boolean operationAdjust, boolean operationAlt,
      boolean operationPointTarget, boolean operationEdgeTarget, boolean operationNextPointTarget,
      boolean cuboidWorldTarget, boolean cuboidFaceTarget, boolean closePathTarget
   ) implements PointerView {}
}
