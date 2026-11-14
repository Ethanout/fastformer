package io.github.fastformer.client.input;

import io.github.fastformer.client.interaction.selectiongesture.AabbAdjustDecision;

import io.github.fastformer.client.session.ClientSessionSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

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
         null, AabbAdjustDecision.SOURCE_CHANGED
      ));
      assertDoesNotThrow(() -> ClientInteractionFeedback.showAabbAdjustFailure(
         null, AabbAdjustDecision.ADJUSTED
      ));
      assertDoesNotThrow(() -> ClientInteractionFeedback.showAabbAdjustFailure(null, null));
   }

   @Test
   void aFinishedAdjustmentReportsNoFailureKey() {
      assertNull(ClientSessionSystem.instance().operations().transforms().aabbAdjustFailureKey(
         AabbAdjustDecision.ADJUSTED
      ));
      assertNull(ClientSessionSystem.instance().operations().transforms().aabbAdjustFailureKey(
         AabbAdjustDecision.DRAG_STARTED
      ));
   }
}
