package io.github.fastformer.client.input.mouse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MousePressRoutingSemanticsTest {
   @Test
   void leftPressUsesWorkspaceBeforeOperationAndGeometryTargets() {
      assertEquals(MousePressRoutingSemantics.LeftTarget.WORKSPACE_POINTER,
         MousePressRoutingSemantics.leftTarget(left(true, false, true, true, true, true, true)));
      assertEquals(MousePressRoutingSemantics.LeftTarget.OPERATION_GIZMO,
         MousePressRoutingSemantics.leftTarget(left(true, false, false, false, true, true, true)));
      assertEquals(MousePressRoutingSemantics.LeftTarget.GEOMETRY_INTERACTION,
         MousePressRoutingSemantics.leftTarget(left(true, false, false, false, false, false, true)));
   }

   @Test
   void leftPressYieldsNearVanillaBlockBeforeAnyFastFormerOwner() {
      assertEquals(MousePressRoutingSemantics.LeftTarget.YIELD_TO_VANILLA,
         MousePressRoutingSemantics.leftTarget(left(true, true, true, true, true, true, true)));
   }

   @Test
   void rightPressKeepsGeometryAndOperationRoutesDifferent() {
      assertEquals(MousePressRoutingSemantics.RightTarget.GEOMETRY_CAPTURE,
         MousePressRoutingSemantics.rightTarget(right(true, false, false, true, false, false, false, false)));
      assertEquals(MousePressRoutingSemantics.RightTarget.YIELD_TO_VANILLA,
         MousePressRoutingSemantics.rightTarget(right(true, false, true, false, false, false, false, false)));
      assertEquals(MousePressRoutingSemantics.RightTarget.YIELD_TO_VANILLA,
         MousePressRoutingSemantics.rightTarget(right(false, true, true, false, false, false, false, false)));
      assertEquals(MousePressRoutingSemantics.RightTarget.OPERATION_POINT,
         MousePressRoutingSemantics.rightTarget(right(false, true, false, false, false, false, false, true)));
   }

   @Test
   void ignoresNonPressOrWrongButton() {
      MousePressRoutingSemantics.LeftState left = left(false, false, true, true, true, true, true);
      assertEquals(MousePressRoutingSemantics.LeftTarget.NONE, MousePressRoutingSemantics.leftTarget(left));
      MousePressRoutingSemantics.RightState right = new MousePressRoutingSemantics.RightState(
         MouseButtonInputSemantics.PRESS, MouseButtonInputSemantics.LEFT_BUTTON,
         false, true, false, false, false, false,
         false, false, false, true, false, false, false, false, false
      );
      assertEquals(MousePressRoutingSemantics.RightTarget.NONE, MousePressRoutingSemantics.rightTarget(right));
   }

   private static MousePressRoutingSemantics.LeftState left(
      boolean press, boolean yield, boolean workspacePointer, boolean workspaceActive,
      boolean operationGizmo, boolean operationPoint, boolean geometryTarget
   ) {
      return new MousePressRoutingSemantics.LeftState(
         press ? MouseButtonInputSemantics.PRESS : MouseButtonInputSemantics.RELEASE,
         MouseButtonInputSemantics.LEFT_BUTTON,
         true, yield, workspacePointer, workspaceActive, operationGizmo, operationPoint,
         false, false, false, false, false, false, false, false, false, false,
         geometryTarget, false, true
      );
   }

   private static MousePressRoutingSemantics.RightState right(
      boolean geometrySession, boolean operationSession, boolean nearVanilla, boolean geometryCaptured,
      boolean workspacePointer, boolean workspaceActive, boolean selectionConfirmed, boolean operationPoint
   ) {
      return new MousePressRoutingSemantics.RightState(
         MouseButtonInputSemantics.PRESS, MouseButtonInputSemantics.RIGHT_BUTTON,
         geometrySession, operationSession, nearVanilla, geometryCaptured, workspacePointer, workspaceActive,
         selectionConfirmed, false, false, operationPoint, false, false, false, false, false
      );
   }
}
