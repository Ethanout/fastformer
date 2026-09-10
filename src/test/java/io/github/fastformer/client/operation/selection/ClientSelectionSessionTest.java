package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.OperationSelectionMode;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClientSelectionSessionTest {
   @Test
   void altStateOwnsTheGestureEvenWhenADraftPointAlreadyExists() {
      ClientSelectionSession session = new ClientSelectionSession();
      session.addDraftPoint(new BlockPos(1, 2, 3));
      session.setAltHeld(true);
      session.refresh(true, false, false);

      assertEquals(ClientSelectionState.ALT_FOCUSED, session.state());
   }

   @Test
   void pointingFocusedAndUnfocusedAreMutuallyExclusiveProjections() {
      ClientSelectionSession session = new ClientSelectionSession();
      session.refresh(false, false, false);
      assertEquals(ClientSelectionState.UNFOCUSED, session.state());

      session.addDraftPoint(BlockPos.ZERO);
      session.refresh(true, false, false);
      assertEquals(ClientSelectionState.POINTING, session.state());

      session.clearDraft();
      session.refresh(true, false, false);
      assertEquals(ClientSelectionState.FOCUSED, session.state());
   }

   @Test
   void selectionModeIsSessionOwnedAndDefaultsToCuboid() {
      ClientSelectionSession session = new ClientSelectionSession();
      assertEquals(OperationSelectionMode.CUBOID, session.selectionMode());
      session.setSelectionMode(OperationSelectionMode.PRISM);
      assertEquals(OperationSelectionMode.PRISM, session.selectionMode());
   }

   @Test
   void cuboidDraftExpansionKeepsItsTwoInputPoints() {
      BlockPos first = new BlockPos(2, 3, 4);
      BlockPos second = new BlockPos(5, 6, 7);
      ClientSelectionSession session = new ClientSelectionSession();
      session.addDraftPoint(first);
      session.addDraftPoint(second);

      assertTrue(session.expandDraftTo(new BlockPos(-1, 9, 3)));
      assertEquals(first, session.draftAt(0));
      assertEquals(second, session.draftAt(1));
      assertEquals(List.of(new BlockPos(-1, 3, 3), new BlockPos(5, 9, 7)), session.selectionDraftPoints());
   }

   @Test
   void removingDraftPointsRecomputesTheAuthoritativeBounds() {
      ClientSelectionSession session = new ClientSelectionSession();
      session.addDraftPoint(new BlockPos(2, 2, 2));
      session.addDraftPoint(new BlockPos(8, 8, 8));
      session.addDraftPoint(new BlockPos(-4, 10, 1));

      assertEquals(new BlockPos(-4, 2, 1), session.draftMinPoint());
      assertEquals(new BlockPos(8, 10, 8), session.draftMaxPoint());
      assertEquals(new BlockPos(-4, 10, 1), session.removeLastDraftPoint());
      assertEquals(new BlockPos(2, 2, 2), session.draftMinPoint());
      assertEquals(new BlockPos(8, 8, 8), session.draftMaxPoint());

      session.removeLastDraftPoint();
      session.removeLastDraftPoint();
      assertNull(session.draftMinPoint());
      assertNull(session.draftMaxPoint());
   }

   @Test
   void restoredDraftBoundsAreNormalizedWithoutChangingInputPoints() {
      ClientSelectionSession session = new ClientSelectionSession();
      BlockPos first = new BlockPos(3, 4, 5);
      BlockPos second = new BlockPos(6, 7, 8);
      session.addDraftPoint(first);
      session.addDraftPoint(second);

      session.restoreDraftBounds(new BlockPos(10, -2, 7), new BlockPos(-3, 12, 1));

      assertEquals(first, session.draftAt(0));
      assertEquals(second, session.draftAt(1));
      assertEquals(new BlockPos(-3, -2, 1), session.draftMinPoint());
      assertEquals(new BlockPos(10, 12, 7), session.draftMaxPoint());
   }
}
