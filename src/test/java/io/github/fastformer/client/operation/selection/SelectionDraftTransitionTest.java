package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SelectionDraftTransitionTest {
   @Test
   void ordinaryMiddleWaitsForFirstLeftPoint() {
      ClientSelectionSession session = new ClientSelectionSession();
      var before = session.draftState();
      assertEquals(SelectionDraftResult.REJECTED, click(session, 2, BlockPos.ZERO, false));
      assertEquals(before, session.draftState());
      assertEquals(SelectionDraftResult.UPDATED, click(session, 0, BlockPos.ZERO, false));
      assertEquals(SelectionDraftResult.READY, click(session, 2, new BlockPos(2, 2, 2), false));
   }

   private static SelectionDraftResult click(ClientSelectionSession session, int button, BlockPos point, boolean alt) {
      return session.onDraftEvent(SelectionDraftEvent.fromMouse(button, point, alt));
   }

   @Test
   void invalidButtonsAndMissingTargetsDoNotChangeDraft() {
      ClientSelectionSession session = new ClientSelectionSession();
      click(session, 0, BlockPos.ZERO, false);
      var before = session.draftState();
      for (int button : List.of(-1, 3, 4)) {
         assertEquals(SelectionDraftResult.REJECTED, click(session, button, BlockPos.ZERO, true));
         assertEquals(before, session.draftState());
      }
      assertEquals(SelectionDraftResult.REJECTED, click(session, 1, null, false));
      assertEquals(before, session.draftState());
   }

   @Test
   void cuboidReadyRetainsDraftUntilPartCreationSucceeds() {
      ClientSelectionSession session = new ClientSelectionSession();
      assertEquals(SelectionDraftResult.UPDATED, click(session, 0, BlockPos.ZERO, false));
      assertEquals(SelectionDraftResult.READY, click(session, 1, new BlockPos(2, 2, 2), false));
      assertEquals(2, session.draftSize());
   }

   @Test
   void altMiddleExpandsBoundsWithoutAddingInputPoints() {
      ClientSelectionSession session = new ClientSelectionSession();
      click(session, 2, BlockPos.ZERO, true);
      click(session, 2, new BlockPos(2, 2, 2), true);
      var points = session.draftPoints();
      assertEquals(SelectionDraftResult.UPDATED, click(session, 2, new BlockPos(-3, 4, 1), true));
      assertEquals(points, session.draftPoints());
      assertEquals(new BlockPos(-3, 0, 0), session.draftMinPoint());
      assertEquals(new BlockPos(2, 4, 2), session.draftMaxPoint());
      assertEquals(SelectionDraftResult.REJECTED, click(session, 2, BlockPos.ZERO, true));
   }

   @Test
   void altRightResetsSecondPointAndExpansionInsteadOfAppendingThirdPoint() {
      ClientSelectionSession session = new ClientSelectionSession();
      click(session, 2, BlockPos.ZERO, true);
      click(session, 2, new BlockPos(2, 2, 2), true);
      click(session, 2, new BlockPos(9, 9, 9), true);
      BlockPos second = new BlockPos(3, 3, 3);
      assertEquals(SelectionDraftResult.READY, click(session, 1, second, true));
      assertEquals(List.of(BlockPos.ZERO, second), session.draftPoints());
      assertEquals(second, session.draftMaxPoint());
   }

   @Test
   void prismUsesSameUndoCloseAndHeightRulesWithOrWithoutAlt() {
      for (boolean alt : List.of(false, true)) {
         ClientSelectionSession session = new ClientSelectionSession();
         session.setSelectionMode(OperationSelectionMode.PRISM);
         assertEquals(SelectionDraftResult.REJECTED, click(session, 0, BlockPos.ZERO, alt));
         click(session, 1, BlockPos.ZERO, alt);
         click(session, 2, new BlockPos(3, 0, 0), alt);
         click(session, 1, new BlockPos(0, 0, 3), alt);
         assertEquals(SelectionDraftResult.UPDATED, click(session, 1, BlockPos.ZERO, alt));
         assertEquals(3, session.prismBaseCount());
         assertEquals(SelectionDraftResult.READY, click(session, 2, new BlockPos(0, 2, 0), alt));
         click(session, 0, BlockPos.ZERO, alt);
         assertEquals(3, session.prismBaseCount());
         click(session, 0, BlockPos.ZERO, alt);
         assertEquals(0, session.prismBaseCount());
         assertEquals(2, session.draftSize());
      }
   }

   @Test
   void capturedTargetDoesNotFollowMutableBlockPosition() {
      BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos(1, 2, 3);
      var event = SelectionDraftEvent.fromMouse(0, target, false);
      target.set(9, 9, 9);
      ClientSelectionSession session = new ClientSelectionSession();
      session.onDraftEvent(event);
      assertEquals(new BlockPos(1, 2, 3), session.draftFirst());
   }
}
