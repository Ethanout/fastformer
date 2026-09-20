package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SubmissionKeyboardSemanticsTest {
   private static final ClientInputStateMachine.Dispatch BLOCKED = ClientInputStateMachine.Dispatch.BLOCKED;
   private static final ClientInputStateMachine.Dispatch BUILDING = ClientInputStateMachine.Dispatch.BUILDING;
   private static final ClientInputStateMachine.Dispatch GEOMETRY = ClientInputStateMachine.Dispatch.GEOMETRY;
   private static final ClientInputStateMachine.Dispatch OPERATION = ClientInputStateMachine.Dispatch.OPERATION;

   @Test
   void enterAndKeypadEnterProduceConfirmationCommands() {
      assertEquals(SubmissionKeyboardSemantics.Command.CONFIRM,
         SubmissionKeyboardSemantics.fromPhysicalKey(1, 257));
      assertEquals(SubmissionKeyboardSemantics.Command.CONFIRM,
         SubmissionKeyboardSemantics.fromPhysicalKey(1, 335));
      assertEquals(SubmissionKeyboardSemantics.Command.NONE,
         SubmissionKeyboardSemantics.fromPhysicalKey(0, 257));
      assertEquals(SubmissionKeyboardSemantics.Command.NONE,
         SubmissionKeyboardSemantics.fromPhysicalKey(1, 32));
   }

   @Test
   void buildingGeometryAndOperationPhasesCanSubmit() {
      for (ClientInputStateMachine.Dispatch route : new ClientInputStateMachine.Dispatch[] {
         BUILDING, GEOMETRY, OPERATION
      }) {
         assertTrue(SubmissionKeyboardSemantics.decide(
            SubmissionKeyboardSemantics.Command.CONFIRM, route, true, false
         ).accepted());
      }
   }

   @Test
   void blockedPhaseAndCandidateKeepTheConfirmationUnconsumed() {
      SubmissionKeyboardSemantics.Decision blocked = SubmissionKeyboardSemantics.decide(
         SubmissionKeyboardSemantics.Command.CONFIRM, BLOCKED, true, false
      );
      assertFalse(blocked.accepted());
      assertEquals(SubmissionKeyboardSemantics.Rejection.PHASE_BLOCKED, blocked.rejection());

      SubmissionKeyboardSemantics.Decision unconfirmed = SubmissionKeyboardSemantics.decide(
         SubmissionKeyboardSemantics.Command.CONFIRM, BUILDING, false, false
      );
      assertFalse(unconfirmed.accepted());
      assertEquals(SubmissionKeyboardSemantics.Rejection.CANDIDATE_UNCONFIRMED, unconfirmed.rejection());
   }

   @Test
   void candidateContextStartsOnlyForAnEligibleConfirmation() {
      assertTrue(SubmissionKeyboardSemantics.requiresCandidateContext(
         SubmissionKeyboardSemantics.Command.CONFIRM, BUILDING
      ));
      assertFalse(SubmissionKeyboardSemantics.requiresCandidateContext(
         SubmissionKeyboardSemantics.Command.CONFIRM, BLOCKED
      ));
      assertFalse(SubmissionKeyboardSemantics.requiresCandidateContext(
         SubmissionKeyboardSemantics.Command.NONE, OPERATION
      ));
   }

   @Test
   void nearVanillaBlockKeepsTheConfirmationForMinecraft() {
      SubmissionKeyboardSemantics.Decision decision = SubmissionKeyboardSemantics.decide(
         SubmissionKeyboardSemantics.Command.CONFIRM, OPERATION, true, true
      );

      assertFalse(decision.accepted());
      assertEquals(SubmissionKeyboardSemantics.Rejection.VANILLA_TARGET, decision.rejection());
   }
}
