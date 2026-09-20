package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MouseDragReleaseSemanticsTest {
   private static final int NONE = -1;

   @Test
   void ignoresNonReleaseAndNonOrdinaryButtons() {
      MouseDragReleaseSemantics.State state = state(MouseButtonInputSemantics.LEFT_BUTTON, NONE, NONE, NONE, NONE, NONE, NONE, false);

      assertEquals(MouseDragReleaseSemantics.Target.NONE,
         MouseDragReleaseSemantics.releaseTarget(MouseButtonInputSemantics.PRESS, MouseButtonInputSemantics.LEFT_BUTTON, state));
      assertEquals(MouseDragReleaseSemantics.Target.NONE,
         MouseDragReleaseSemantics.releaseTarget(MouseButtonInputSemantics.RELEASE, MouseButtonInputSemantics.MIDDLE_BUTTON, state));
   }

   @Test
   void leftReleaseKeepsTheExistingDragAndCapturePriority() {
      assertEquals(MouseDragReleaseSemantics.Target.OPERATION_POINT_DRAG, releaseLeft(
         state(0, 0, 0, 0, 0, 0, 0, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.OPERATION_DRAG, releaseLeft(
         state(NONE, 0, 0, 0, 0, 0, 0, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.WORKSPACE_GIZMO_DRAG, releaseLeft(
         state(NONE, NONE, 0, 0, 0, 0, 0, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.WORKSPACE_FACE_DRAG, releaseLeft(
         state(NONE, NONE, NONE, 0, 0, 0, 0, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.GEOMETRY_GIZMO_DRAG, releaseLeft(
         state(NONE, NONE, NONE, NONE, 0, 0, 0, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.OPERATION_CAPTURE, releaseLeft(
         state(NONE, NONE, NONE, NONE, NONE, 0, 0, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.GEOMETRY_CAPTURE, releaseLeft(
         state(NONE, NONE, NONE, NONE, NONE, NONE, 0, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.UNDO_PRESS, releaseLeft(
         state(NONE, NONE, NONE, NONE, NONE, NONE, NONE, true)
      ));
   }

   @Test
   void rightReleaseKeepsTheExistingDragAndCapturePriority() {
      assertEquals(MouseDragReleaseSemantics.Target.OPERATION_POINT_DRAG, releaseRight(
         state(1, 1, 1, 1, 1, 1, 1, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.OPERATION_DRAG, releaseRight(
         state(NONE, 1, 1, 1, 1, 1, 1, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.WORKSPACE_GIZMO_DRAG, releaseRight(
         state(NONE, NONE, 1, 1, 1, 1, 1, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.WORKSPACE_FACE_DRAG, releaseRight(
         state(NONE, NONE, NONE, 1, 1, 1, 1, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.GEOMETRY_GIZMO_DRAG, releaseRight(
         state(NONE, NONE, NONE, NONE, 1, 1, 1, true)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.OPERATION_CAPTURE, releaseRight(
         state(NONE, NONE, NONE, NONE, NONE, 1, 1, true)
      ));
   }

   @Test
   void rightReleaseDoesNotConsumeLeftOnlyCaptureOrUndoState() {
      assertEquals(MouseDragReleaseSemantics.Target.NONE, releaseRight(
         state(NONE, NONE, NONE, NONE, NONE, NONE, 1, true)
      ));
   }

   @Test
   void releasesOnlyTheDragOwnedByTheMatchingButton() {
      assertEquals(MouseDragReleaseSemantics.Target.NONE, releaseLeft(
         state(MouseButtonInputSemantics.RIGHT_BUTTON, NONE, NONE, NONE, NONE, NONE, NONE, false)
      ));
      assertEquals(MouseDragReleaseSemantics.Target.NONE, releaseRight(
         state(MouseButtonInputSemantics.LEFT_BUTTON, NONE, NONE, NONE, NONE, NONE, NONE, false)
      ));
   }

   private static MouseDragReleaseSemantics.Target releaseLeft(MouseDragReleaseSemantics.State state) {
      return MouseDragReleaseSemantics.releaseTarget(
         MouseButtonInputSemantics.RELEASE, MouseButtonInputSemantics.LEFT_BUTTON, state
      );
   }

   private static MouseDragReleaseSemantics.Target releaseRight(MouseDragReleaseSemantics.State state) {
      return MouseDragReleaseSemantics.releaseTarget(
         MouseButtonInputSemantics.RELEASE, MouseButtonInputSemantics.RIGHT_BUTTON, state
      );
   }

   private static MouseDragReleaseSemantics.State state(
      int operationPointDragButton,
      int operationDragButton,
      int workspaceGizmoDragButton,
      int workspaceFaceDragButton,
      int geometryGizmoDragButton,
      int operationCaptureButton,
      int geometryCaptureButton,
      boolean undoPressCaptured
   ) {
      return new MouseDragReleaseSemantics.State(
         operationPointDragButton,
         operationDragButton,
         workspaceGizmoDragButton,
         workspaceFaceDragButton,
         geometryGizmoDragButton,
         operationCaptureButton,
         geometryCaptureButton,
         undoPressCaptured
      );
   }
}
