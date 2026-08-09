package io.github.fastformer.client;

import io.github.fastformer.fastplace.OperationSelectionMode;

final class OperationInputSemantics {
   private OperationInputSemantics() {
   }

   static LeftAction leftAction(
      OperationSelectionMode selectionMode,
      boolean selectionReady,
      boolean adjustmentStarted,
      boolean gizmoHit
   ) {
      if (selectionReady && gizmoHit) {
         return LeftAction.GIZMO_DRAG;
      }
      if (adjustmentStarted) {
         return LeftAction.ADJUSTMENT_UNDO;
      }
      return selectionMode == OperationSelectionMode.PRISM
         ? LeftAction.SELECTION_UNDO
         : LeftAction.VANILLA;
   }

   static boolean yieldToVanillaNearBlock(
      boolean nearBlock, boolean modifierOverride, boolean gizmoHit
   ) {
      return nearBlock && !modifierOverride && !gizmoHit;
   }

   enum LeftAction {
      VANILLA,
      SELECTION_UNDO,
      ADJUSTMENT_UNDO,
      GIZMO_DRAG
   }
}
