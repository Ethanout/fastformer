package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FastPlaceActivity;
import org.junit.jupiter.api.Test;

class WorkspaceSubmissionHudTest {
   @Test
   void pendingTaskDoesNotOfferTheCancelKeyThatInputRejects() {
      assertFalse(WorkspaceSubmissionHud.showsCancelHint(
         io.github.fastformer.fastplace.FastPlaceActivity.OPERATION_TASK, true));
      assertTrue(WorkspaceSubmissionHud.showsCancelHint(
         io.github.fastformer.fastplace.FastPlaceActivity.OPERATION_TASK, false));
      assertFalse(WorkspaceSubmissionHud.showsCancelHint(
         io.github.fastformer.fastplace.FastPlaceActivity.RESTORE_TASK, false));
   }

   @Test
   void pendingSubmissionKeepsFillStatusWithoutAnActionKey() {
      assertEquals("fastformer.message.fill_mode_status", WorkspaceSubmissionHud.fillModeKey(true));
      assertEquals("fastformer.message.fill_mode", WorkspaceSubmissionHud.fillModeKey(false));
   }

   private static final boolean PENDING = true;
   private static final boolean NOT_PENDING = false;
   private static final boolean TASK = true;
   private static final boolean NO_TASK = false;
   private static final boolean QUICK_REPLACE = true;
   private static final boolean NO_QUICK_REPLACE = false;
   private static final boolean RECONNECT = true;
   private static final boolean NO_RECONNECT = false;

   private static final int FILL_LINE_Y = WorkspaceSubmissionHud.FILL_LINE_Y;
   private static final int QUICK_REPLACE_OR_TASK_LINE_Y = WorkspaceSubmissionHud.QUICK_REPLACE_OR_TASK_LINE_Y;
   private static final int RECONNECT_LINE_Y = WorkspaceSubmissionHud.RECONNECT_LINE_Y;

   @Test
   void aWaitingSubmissionShowsThePendingText() {
      WorkspaceSubmissionHud.Plan plan = WorkspaceSubmissionHud.plan(
         PENDING, NO_TASK, NO_QUICK_REPLACE, NO_RECONNECT
      );

      assertTrue(plan.showsWaitingLine());
      assertEquals(WorkspaceSubmissionHud.WAITING_KEY, plan.waitingKey());
      assertEquals("fastformer.message.operation_submit_pending", plan.waitingKey());
   }

   @Test
   void theWaitingTextYieldsToTheActivityLine() {
      // An activity line already reports that work runs, so the local line stops.
      WorkspaceSubmissionHud.Plan plan = WorkspaceSubmissionHud.plan(
         PENDING, TASK, NO_QUICK_REPLACE, NO_RECONNECT
      );

      assertFalse(plan.showsWaitingLine());
      assertNull(plan.waitingKey());
   }

   @Test
   void aFinishedSubmissionShowsNoWaitingText() {
      assertFalse(WorkspaceSubmissionHud.plan(
         NOT_PENDING, NO_TASK, NO_QUICK_REPLACE, NO_RECONNECT
      ).showsWaitingLine());
      assertFalse(WorkspaceSubmissionHud.plan(
         NOT_PENDING, TASK, NO_QUICK_REPLACE, NO_RECONNECT
      ).showsWaitingLine());
   }

   @Test
   void everyWaitingFrameSuppressesTheActionPrompts() {
      // The workspace refuses build and selection actions for the whole wait,
      // including the frames where the activity line replaced the waiting text.
      assertTrue(WorkspaceSubmissionHud.plan(
         PENDING, NO_TASK, NO_QUICK_REPLACE, NO_RECONNECT
      ).suppressActionPrompts());
      assertTrue(WorkspaceSubmissionHud.plan(
         PENDING, TASK, NO_QUICK_REPLACE, NO_RECONNECT
      ).suppressActionPrompts());
      assertFalse(WorkspaceSubmissionHud.plan(
         NOT_PENDING, NO_TASK, NO_QUICK_REPLACE, NO_RECONNECT
      ).suppressActionPrompts());
   }

   @Test
   void aClientOnlySubmissionKeepsTheHudAlive() {
      // A paste submission from a local clipboard leaves every server flag false,
      // including the controller. Without the pending flag the frame returns early.
      assertTrue(WorkspaceSubmissionHud.hudActive(
         NO_RECONNECT, false, false, false, false, NO_TASK, NO_QUICK_REPLACE, PENDING
      ));
   }

   @Test
   void anIdleFrameStillLeavesTheHudToVanilla() {
      assertFalse(WorkspaceSubmissionHud.hudActive(
         NO_RECONNECT, false, false, false, false, NO_TASK, NO_QUICK_REPLACE, NOT_PENDING
      ));
   }

   @Test
   void everyOtherElementStillKeepsTheHudAlive() {
      assertTrue(WorkspaceSubmissionHud.hudActive(
         RECONNECT, false, false, false, false, NO_TASK, NO_QUICK_REPLACE, NOT_PENDING
      ));
      assertTrue(WorkspaceSubmissionHud.hudActive(
         NO_RECONNECT, true, false, false, false, NO_TASK, NO_QUICK_REPLACE, NOT_PENDING
      ));
      assertTrue(WorkspaceSubmissionHud.hudActive(
         NO_RECONNECT, false, true, false, false, NO_TASK, NO_QUICK_REPLACE, NOT_PENDING
      ));
      assertTrue(WorkspaceSubmissionHud.hudActive(
         NO_RECONNECT, false, false, true, false, NO_TASK, NO_QUICK_REPLACE, NOT_PENDING
      ));
      assertTrue(WorkspaceSubmissionHud.hudActive(
         NO_RECONNECT, false, false, false, true, NO_TASK, NO_QUICK_REPLACE, NOT_PENDING
      ));
      assertTrue(WorkspaceSubmissionHud.hudActive(
         NO_RECONNECT, false, false, false, false, TASK, NO_QUICK_REPLACE, NOT_PENDING
      ));
      assertTrue(WorkspaceSubmissionHud.hudActive(
         NO_RECONNECT, false, false, false, false, NO_TASK, QUICK_REPLACE, NOT_PENDING
      ));
   }

   @Test
   void theWaitingLineMissesEveryLineInUse() {
      int y = WorkspaceSubmissionHud.waitingLineY(false, false);

      assertNotEquals(FILL_LINE_Y, y);
      assertEquals(QUICK_REPLACE_OR_TASK_LINE_Y, y);
   }

   @Test
   void quickReplaceAndTheActivityLinePushTheWaitingLineDown() {
      // Quick Replace and the activity task line both own y=20, so the waiting
      // text moves to the line below them.
      int y = WorkspaceSubmissionHud.waitingLineY(true, false);

      assertNotEquals(QUICK_REPLACE_OR_TASK_LINE_Y, y);
      assertEquals(RECONNECT_LINE_Y, y);
   }

   @Test
   void theWaitingLineNeverCoversTheReconnectPrompt() {
      // The reconnect prompt owns y=32, so the waiting text stays at y=20 there.
      int y = WorkspaceSubmissionHud.waitingLineY(false, true);

      assertNotEquals(RECONNECT_LINE_Y, y);
      assertEquals(QUICK_REPLACE_OR_TASK_LINE_Y, y);
   }

   @Test
   void allThreeElementsTogetherStayOnSeparateLines() {
      int y = WorkspaceSubmissionHud.waitingLineY(true, true);

      assertNotEquals(FILL_LINE_Y, y);
      assertNotEquals(QUICK_REPLACE_OR_TASK_LINE_Y, y);
      assertNotEquals(RECONNECT_LINE_Y, y);
      assertEquals(RECONNECT_LINE_Y + 12, y);
   }

   @Test
   void theWaitingLineMovesAwayFromEveryOwnerAcrossTheWholeTable() {
      // The layout entry, not four boolean synonyms: every combination of the
      // three owners must leave the waiting text on an unowned line.
      for (boolean quickReplaceOrTask : new boolean[] { false, true }) {
         for (boolean reconnectPending : new boolean[] { false, true }) {
            int y = WorkspaceSubmissionHud.waitingLineY(quickReplaceOrTask, reconnectPending);

            assertNotEquals(FILL_LINE_Y, y, "fill line taken");
            if (quickReplaceOrTask) {
               assertNotEquals(QUICK_REPLACE_OR_TASK_LINE_Y, y, "quick replace or activity line taken");
            }
            if (reconnectPending) {
               assertNotEquals(RECONNECT_LINE_Y, y, "reconnect line taken");
            }
         }
      }
   }

   // The rest of the tests drive the real consumers with their real arguments.

   @Test
   void aPendingSubmissionDropsTheBlockKeyFromTheFillText() {
      // The lock blocks the block key. The status text keeps the fill mode.
      assertEquals(
         WorkspaceSubmissionHud.FILL_MODE_STATUS_KEY,
         WorkspaceSubmissionHud.fillModeKey(PENDING)
      );
      assertEquals(
         WorkspaceSubmissionHud.FILL_MODE_KEY,
         WorkspaceSubmissionHud.fillModeKey(NOT_PENDING)
      );
   }

   @Test
   void theFillStatusTextCarriesNoKey() {
      // The status text takes one argument. A second argument would break it.
      assertEquals("fastformer.message.fill_mode_status", WorkspaceSubmissionHud.FILL_MODE_STATUS_KEY);
      assertNotEquals(WorkspaceSubmissionHud.FILL_MODE_KEY, WorkspaceSubmissionHud.FILL_MODE_STATUS_KEY);
   }

   @Test
   void theActivityLineOffersTheCancelHintWhileNoSubmissionWaits() {
      // Only a task reports the hint. Both cancellable tasks keep it.
      assertTrue(WorkspaceSubmissionHud.showsCancelHint(FastPlaceActivity.OPERATION_TASK, NOT_PENDING));
      assertTrue(WorkspaceSubmissionHud.showsCancelHint(FastPlaceActivity.PLACEMENT_TASK, NOT_PENDING));
   }

   @Test
   void theActivityLineDropsTheCancelHintWhileASubmissionWaits() {
      // The tracker stays pending through the phase where the server task is
      // active, and the cancel key reports the pending state instead of
      // cancelling there. The exit hint would be false.
      assertFalse(WorkspaceSubmissionHud.showsCancelHint(FastPlaceActivity.OPERATION_TASK, PENDING));
      assertFalse(WorkspaceSubmissionHud.showsCancelHint(FastPlaceActivity.PLACEMENT_TASK, PENDING));
   }

   @Test
   void theActivityLineKeepsItsOtherConditions() {
      // The caller draws this line only for a task, and the restore task never
      // offers cancel. A session reports no task, so it never reaches this hint.
      assertFalse(WorkspaceSubmissionHud.showsCancelHint(FastPlaceActivity.RESTORE_TASK, NOT_PENDING));
      assertFalse(WorkspaceSubmissionHud.showsCancelHint(FastPlaceActivity.OPERATION_SESSION, NOT_PENDING));
      assertFalse(WorkspaceSubmissionHud.showsCancelHint(FastPlaceActivity.BUILDING_SESSION, NOT_PENDING));
      assertFalse(WorkspaceSubmissionHud.showsCancelHint(FastPlaceActivity.GEOMETRY_SESSION, NOT_PENDING));
      assertFalse(WorkspaceSubmissionHud.showsCancelHint(FastPlaceActivity.NONE, NOT_PENDING));
   }
}
