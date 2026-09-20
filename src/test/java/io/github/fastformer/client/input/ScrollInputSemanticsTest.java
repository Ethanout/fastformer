package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScrollInputSemanticsTest {
   @Test
   void blocksAnyScrollInABlockedPhase() {
      ScrollInputSemantics.Decision decision = ScrollInputSemantics.decide(
         0.0,
         ClientInputStateMachine.Dispatch.BLOCKED,
         true,
         true,
         false,
         false
      );

      assertEquals(ScrollInputSemantics.Command.BLOCK, decision.command());
      assertEquals(0, decision.direction());
   }

   @Test
   void workspaceMoveOwnsOperationScrollBeforeCandidateScroll() {
      ScrollInputSemantics.Decision decision = ScrollInputSemantics.decide(
         -1.0,
         ClientInputStateMachine.Dispatch.OPERATION,
         true,
         true,
         false,
         true
      );

      assertEquals(ScrollInputSemantics.Command.WORKSPACE_MOVE, decision.command());
      assertEquals(-1, decision.direction());
   }

   @Test
   void candidateScrollRequiresAnActiveNonVanillaContext() {
      ScrollInputSemantics.Decision decision = ScrollInputSemantics.decide(
         1.0,
         ClientInputStateMachine.Dispatch.BUILDING,
         false,
         true,
         false,
         false
      );

      assertEquals(ScrollInputSemantics.Command.CANDIDATE_SCROLL, decision.command());
      assertEquals(1, decision.direction());
   }

   @Test
   void modifierAndVanillaTargetsYieldWithoutASessionCommand() {
      assertEquals(
         ScrollInputSemantics.Command.YIELD_TO_VANILLA,
         ScrollInputSemantics.decide(
            1.0,
            ClientInputStateMachine.Dispatch.BUILDING,
            false,
            true,
            true,
            false
         ).command()
      );
      assertEquals(
         ScrollInputSemantics.Command.NONE,
         ScrollInputSemantics.decide(
            1.0,
            ClientInputStateMachine.Dispatch.BUILDING,
            false,
            true,
            false,
            true
         ).command()
      );
      assertEquals(
         ScrollInputSemantics.Command.NONE,
         ScrollInputSemantics.decide(
            0.0,
            ClientInputStateMachine.Dispatch.BUILDING,
            false,
            true,
            false,
            false
         ).command()
      );
   }
}
