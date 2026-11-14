package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.input.policy.DragAdvanceSemantics;
import io.github.fastformer.client.input.state.PointerGestureState;
import net.minecraft.client.Minecraft;

/** Advances one pointer drag owner per client tick. */
final class PointerDragTicker {
   private PointerDragTicker() { }

   static void advance(Minecraft minecraft, ClientInputSession session, boolean controlHeld) {
      boolean workspaceGizmo = ClientSessionSystem.instance().operations().selectionGestures().gizmo() != null;
      boolean workspaceFace = ClientSessionSystem.instance().operations().selectionGestures().face() != null;
      if (session.operationDrag == null && session.operationPointDrag == null
         && session.geometryGizmoDrag == null && !workspaceGizmo && !workspaceFace) return;

      DragAdvanceSemantics.Plan plan = DragAdvanceSemantics.plan(new DragAdvanceSemantics.State(
         session.operationDrag != null,
         session.pointerGesture.owns(session.pointerGesture.activeToken(), PointerGestureState.Kind.OPERATION_FACE)
            || session.pointerGesture.owns(session.pointerGesture.activeToken(), PointerGestureState.Kind.OPERATION_GIZMO),
         session.operationPointDrag != null,
         session.pointerGesture.owns(session.pointerGesture.activeToken(), PointerGestureState.Kind.OPERATION_POINT),
         session.geometryGizmoDrag != null,
         workspaceGizmo,
         workspaceFace,
         ClientSessionSystem.instance().operationActive()
      ));
      if (plan.clearOperationDrag()) OperationDragController.cancel(session);
      if (plan.clearOperationPointDrag()) OperationPointInputController.cancel(session);
      if (plan.exclusiveOwner() == DragAdvanceSemantics.Owner.WORKSPACE_FACE) {
         SelectionGestureController.updateFace(session, minecraft);
         return;
      }
      if (plan.exclusiveOwner() == DragAdvanceSemantics.Owner.WORKSPACE_GIZMO) {
         SelectionGestureController.updateGizmo(session, minecraft, controlHeld);
         return;
      }
      if (plan.advanceGeometryGizmo()) GeometryDragController.update(minecraft, session, controlHeld);
      if (plan.advanceOperationPoint()) OperationPointInputController.updateOperationPointDrag(minecraft, session);
      if (plan.clearInactiveOperationDrags()) {
         OperationDragController.cancel(session);
         OperationPointInputController.cancel(session);
         return;
      }
      if (plan.advanceOperationDrag()) OperationDragController.update(minecraft, session);
   }
}
