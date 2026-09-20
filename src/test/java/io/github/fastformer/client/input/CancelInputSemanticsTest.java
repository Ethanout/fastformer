package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CancelInputSemanticsTest {
   @Test
   void cancelRequiresTheConfiguredKeyPressAndAReadyChannel() {
      assertEquals(CancelInputSemantics.Command.IGNORE, decide(0, 81, true, true, false).command());
      assertEquals(CancelInputSemantics.Command.IGNORE, decide(1, 80, true, true, false).command());
      assertEquals(CancelInputSemantics.Command.IGNORE, decide(1, 81, false, true, false).command());
      assertEquals(CancelInputSemantics.Command.IGNORE, decide(1, 81, true, false, false).command());
      assertEquals(CancelInputSemantics.Command.REQUEST_CANCEL, decide(1, 81, true, true, false).command());
   }

   @Test
   void pendingSubmissionReportsInsteadOfCancellingOrDismissingRestore() {
      CancelInputSemantics.Decision decision = CancelInputSemantics.decide(
         1, 81, true, true, true, true, true
      );

      assertEquals(CancelInputSemantics.Command.REPORT_SUBMISSION_PENDING, decision.command());
      assertFalse(decision.dismissOperationRestore());
      assertFalse(decision.dismissPreviewRestore());
   }

   @Test
   void acceptedCancelCarriesBothRestoreDismissals() {
      CancelInputSemantics.Decision decision = CancelInputSemantics.decide(
          1, 81, true, true, false, true, true
      );

      assertEquals(CancelInputSemantics.Command.REQUEST_CANCEL, decision.command());
      assertTrue(decision.dismissOperationRestore());
      assertTrue(decision.dismissPreviewRestore());
   }

   @Test
   void escapeCancelsAnActiveSessionBeforeThePauseScreen() {
      CancelInputSemantics.Decision decision = escape(true, true, true, false, true, true);

      assertEquals(CancelInputSemantics.Command.REQUEST_CANCEL, decision.command());
      assertTrue(decision.dismissOperationRestore());
      assertTrue(decision.dismissPreviewRestore());
   }

   @Test
   void escapeKeepsTheVanillaPauseWithoutACancellableSession() {
      assertEquals(CancelInputSemantics.Command.IGNORE, escape(true, true, false, false, false, false).command());
      assertEquals(CancelInputSemantics.Command.IGNORE, escape(false, true, true, false, false, false).command());
      assertEquals(CancelInputSemantics.Command.IGNORE, escape(true, false, true, false, false, false).command());
   }

   @Test
   void escapeReportsAPendingSubmissionAndLeavesThePauseAvailable() {
      CancelInputSemantics.Decision decision = escape(true, true, true, true, false, false);

      assertEquals(CancelInputSemantics.Command.REPORT_SUBMISSION_PENDING, decision.command());
      assertFalse(decision.dismissOperationRestore());
      assertFalse(decision.dismissPreviewRestore());
   }

   @Test
   void theQueuedVanillaDropClickIsConsumedOnlyForAMatchingAcceptedCancel() {
      CancelInputSemantics.Decision accepted = CancelInputSemantics.decide(
         1, 81, true, true, false, false, false
      );
      CancelInputSemantics.Decision reported = CancelInputSemantics.decide(
         1, 81, true, true, true, false, false
      );

      assertTrue(CancelInputSemantics.consumesVanillaDrop(accepted, true, true));
      assertFalse(CancelInputSemantics.consumesVanillaDrop(accepted, true, false));
      assertFalse(CancelInputSemantics.consumesVanillaDrop(accepted, false, true));
      assertFalse(CancelInputSemantics.consumesVanillaDrop(reported, true, true));
   }

   private static CancelInputSemantics.Decision escape(
      boolean clientReady,
      boolean channelAvailable,
      boolean sessionCancellable,
      boolean submissionPending,
      boolean operationRestorePending,
      boolean previewRestorePending
   ) {
      return CancelInputSemantics.decideEscape(
         clientReady, channelAvailable, sessionCancellable,
         submissionPending, operationRestorePending, previewRestorePending
      );
   }

   private static CancelInputSemantics.Decision decide(
      int action, int key, boolean clientReady, boolean channelAvailable, boolean submissionPending
   ) {
      return CancelInputSemantics.decide(
         action, key, clientReady, channelAvailable, submissionPending, false, false
      );
   }
}
