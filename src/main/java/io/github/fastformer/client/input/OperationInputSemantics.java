package io.github.fastformer.client.input;

import io.github.fastformer.fastplace.OperationSelectionMode;

public final class OperationInputSemantics {
   private OperationInputSemantics() {
   }

   public static LeftAction leftAction(
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

   public static boolean yieldToVanillaNearBlock(
      boolean nearBlock, boolean modifierOverride, boolean gizmoHit
   ) {
      return nearBlock && !modifierOverride && !gizmoHit;
   }

   public enum LeftAction {
      VANILLA,
      SELECTION_UNDO,
      ADJUSTMENT_UNDO,
      GIZMO_DRAG
   }
}
