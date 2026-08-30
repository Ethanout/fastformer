package io.github.fastformer.client;

import io.github.fastformer.client.input.OperationInputSemantics;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.OperationSelectionMode;
import org.junit.jupiter.api.Test;

class OperationInputSemanticsTest {
   @Test
   void gizmoHitAlwaysWinsForAReadySelection() {
      assertEquals(
         OperationInputSemantics.LeftAction.GIZMO_DRAG,
         OperationInputSemantics.leftAction(OperationSelectionMode.PRISM, true, true, true)
      );
      assertEquals(
         OperationInputSemantics.LeftAction.GIZMO_DRAG,
         OperationInputSemantics.leftAction(OperationSelectionMode.CUBOID, true, false, true)
      );
   }

   @Test
   void prismLeftUndoesSelectionUntilAdjustmentStarts() {
      assertEquals(
         OperationInputSemantics.LeftAction.SELECTION_UNDO,
         OperationInputSemantics.leftAction(OperationSelectionMode.PRISM, false, false, false)
      );
      assertEquals(
         OperationInputSemantics.LeftAction.ADJUSTMENT_UNDO,
         OperationInputSemantics.leftAction(OperationSelectionMode.PRISM, true, true, false)
      );
   }

   @Test
   void cuboidLeftRetainsItsNormalMeaningUntilAdjustmentStarts() {
      assertEquals(
         OperationInputSemantics.LeftAction.VANILLA,
         OperationInputSemantics.leftAction(OperationSelectionMode.CUBOID, true, false, false)
      );
      assertEquals(
         OperationInputSemantics.LeftAction.ADJUSTMENT_UNDO,
         OperationInputSemantics.leftAction(OperationSelectionMode.CUBOID, true, true, false)
      );
   }

   @Test
   void nearBlockAlwaysReturnsToVanillaWithoutModifierOrGizmoOverride() {
      assertEquals(true, OperationInputSemantics.yieldToVanillaNearBlock(true, false, false));
      assertEquals(false, OperationInputSemantics.yieldToVanillaNearBlock(true, true, false));
      assertEquals(false, OperationInputSemantics.yieldToVanillaNearBlock(true, false, true));
      assertEquals(false, OperationInputSemantics.yieldToVanillaNearBlock(false, false, false));
   }
}
