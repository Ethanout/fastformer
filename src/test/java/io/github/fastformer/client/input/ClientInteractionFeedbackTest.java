package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import org.junit.jupiter.api.Test;

class ClientInteractionFeedbackTest {
   @Test
   void resultKeyUsesTheMatchingOutcome() {
      assertEquals("success", ClientInteractionFeedback.resultKey(true, "success", "failure"));
      assertEquals("failure", ClientInteractionFeedback.resultKey(false, "success", "failure"));
   }

   @Test
   void aBlockedAdjustmentShowsItsReasonWithoutAClient() {
      // The message holds no client state, so a headless call must stay safe.
      assertDoesNotThrow(() -> ClientInteractionFeedback.showAabbAdjustFailure(
         null, ClientOperationController.AabbAdjustDecision.SOURCE_CHANGED
      ));
      assertDoesNotThrow(() -> ClientInteractionFeedback.showAabbAdjustFailure(
         null, ClientOperationController.AabbAdjustDecision.ADJUSTED
      ));
      assertDoesNotThrow(() -> ClientInteractionFeedback.showAabbAdjustFailure(null, null));
   }

   @Test
   void aFinishedAdjustmentReportsNoFailureKey() {
      assertNull(ClientOperationController.aabbAdjustFailureKey(
         ClientOperationController.AabbAdjustDecision.ADJUSTED
      ));
      assertNull(ClientOperationController.aabbAdjustFailureKey(
         ClientOperationController.AabbAdjustDecision.DRAG_STARTED
      ));
   }
}
