package io.github.fastformer.client.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkspaceInputSemanticsTest {
   @Test
   void controlWorldClickCreatesWhileControlEditorHitTogglesSelection() {
      assertEquals(
         WorkspaceInputSemantics.ClickAction.CREATE_PART,
         WorkspaceInputSemantics.click(true, WorkspaceInputSemantics.Target.WORLD)
      );
      for (WorkspaceInputSemantics.Target target : new WorkspaceInputSemantics.Target[]{
         WorkspaceInputSemantics.Target.GIZMO,
         WorkspaceInputSemantics.Target.LABEL,
         WorkspaceInputSemantics.Target.FRAME
      }) {
         assertEquals(
            WorkspaceInputSemantics.ClickAction.TOGGLE_PART,
            WorkspaceInputSemantics.click(true, target)
         );
         assertEquals(
            WorkspaceInputSemantics.ClickAction.SELECT_PART,
            WorkspaceInputSemantics.click(false, target)
         );
      }
   }

   @Test
   void dragSelectionDependsOnMembershipAndControl() {
      assertEquals(
         WorkspaceInputSemantics.DragAction.DRAG_SELECTION,
         WorkspaceInputSemantics.drag(true, false)
      );
      assertEquals(
         WorkspaceInputSemantics.DragAction.SELECT_ONLY_AND_DRAG,
         WorkspaceInputSemantics.drag(false, false)
      );
      assertEquals(
         WorkspaceInputSemantics.DragAction.ADD_AND_DRAG,
         WorkspaceInputSemantics.drag(false, true)
      );
   }

   @Test
   void altReleaseCyclesOnlyWhenNoAlternativeGestureWasConsumed() {
      assertEquals(true, WorkspaceInputSemantics.cycleOnAltRelease(true, false));
      assertEquals(false, WorkspaceInputSemantics.cycleOnAltRelease(true, true));
      assertEquals(false, WorkspaceInputSemantics.cycleOnAltRelease(false, false));
   }
}
