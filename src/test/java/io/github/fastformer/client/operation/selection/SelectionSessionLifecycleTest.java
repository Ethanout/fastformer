package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SelectionSessionLifecycleTest {
   @Test
   void transitionsAreExplicitAndExitOwnsCleanupDecision() {
      var session = new ClientSelectionSession();
      assertEquals(SelectionSessionLifecycle.Phase.IDLE, session.lifecyclePhase());
      assertTrue(session.onLifecycleEvent(SelectionSessionLifecycle.Event.BEGIN).changed());
      assertEquals(SelectionSessionLifecycle.Phase.POINTING, session.lifecyclePhase());
      session.addDraftPoint(BlockPos.ZERO);
      session.onLifecycleEvent(SelectionSessionLifecycle.Event.FOCUS);
      assertEquals(SelectionSessionLifecycle.Phase.FOCUSED, session.lifecyclePhase());
      assertEquals(SelectionSessionLifecycle.Phase.SUBMITTING,
         session.onLifecycleEvent(SelectionSessionLifecycle.Event.SUBMIT).target());
      session.onLifecycleEvent(SelectionSessionLifecycle.Event.COMPLETE);
      assertEquals(SelectionSessionLifecycle.Phase.FOCUSED, session.lifecyclePhase());
   }

   @Test
   void environmentExitKeepsDataUntilExplicitCleanup() {
      var session = new ClientSelectionSession();
      session.workspace().addParts(java.util.List.of(
         io.github.fastformer.client.operation.model.ClientSelectionPart.empty(
            io.github.fastformer.client.operation.model.ClientSelectionPart.Source.CLIPBOARD)));
      session.addDraftPoint(BlockPos.ZERO);
      session.clearTransientInteraction();
      assertEquals(SelectionSessionLifecycle.Phase.IDLE, session.lifecyclePhase());
      assertEquals(1, session.workspace().size());
      assertTrue(session.hasDraft());
      session.clearLiveInteraction();
      assertTrue(session.workspace().isEmpty());
      assertFalse(session.hasDraft());
   }

   @Test
   void rejectedDraftDoesNotAdvanceLifecycle() {
      var session = new ClientSelectionSession();
      assertEquals(SelectionDraftResult.REJECTED,
         session.onDraftEvent(SelectionDraftEvent.fromMouse(1, null, false)));
      assertEquals(SelectionSessionLifecycle.Phase.IDLE, session.lifecyclePhase());
   }
}
