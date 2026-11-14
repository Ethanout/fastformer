package io.github.fastformer.common.workspace.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkspaceInputSemanticsTest {
   @Test
   void controlWorldClickCreatesWhileControlEditorHitTogglesSelection() {
      assertEquals(
         WorkspaceInputRules.ClickAction.CREATE_PART,
         WorkspaceInputRules.click(true, WorkspaceInputRules.Target.WORLD)
      );
      for (WorkspaceInputRules.Target target : new WorkspaceInputRules.Target[]{
         WorkspaceInputRules.Target.GIZMO,
         WorkspaceInputRules.Target.LABEL,
         WorkspaceInputRules.Target.FRAME
      }) {
         assertEquals(
            WorkspaceInputRules.ClickAction.TOGGLE_PART,
            WorkspaceInputRules.click(true, target)
         );
         assertEquals(
            WorkspaceInputRules.ClickAction.SELECT_PART,
            WorkspaceInputRules.click(false, target)
         );
      }
   }

   @Test
   void dragSelectionDependsOnMembershipAndControl() {
      assertEquals(
         WorkspaceInputRules.DragAction.DRAG_SELECTION,
         WorkspaceInputRules.drag(true, false)
      );
      assertEquals(
         WorkspaceInputRules.DragAction.SELECT_ONLY_AND_DRAG,
         WorkspaceInputRules.drag(false, false)
      );
      assertEquals(
         WorkspaceInputRules.DragAction.ADD_AND_DRAG,
         WorkspaceInputRules.drag(false, true)
      );
   }

   @Test
   void altReleaseCyclesOnlyWhenNoAlternativeGestureWasConsumed() {
      assertEquals(true, WorkspaceInputRules.cycleOnAltRelease(true, false));
      assertEquals(false, WorkspaceInputRules.cycleOnAltRelease(true, true));
      assertEquals(false, WorkspaceInputRules.cycleOnAltRelease(false, false));
   }
}
