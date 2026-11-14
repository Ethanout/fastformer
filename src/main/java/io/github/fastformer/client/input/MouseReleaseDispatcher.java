package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;

import io.github.fastformer.client.interaction.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.client.interaction.selectiongesture.WorkspaceFaceDrag;
import io.github.fastformer.client.interaction.selectiongesture.WorkspaceGizmoDrag;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.mouse.MouseDragReleaseSemantics;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import io.github.fastformer.protocol.c2s.placement.UndoFastPlacePayload;

final class MouseReleaseDispatcher {
   private static final long UNDO_SHORT_PRESS_NANOS = 250_000_000L;

   private MouseReleaseDispatcher() { }

   static boolean finish(Minecraft minecraft, ClientInputSession session, int action, int button, long releasedAtNanos) {
      return switch (target(session, action, button)) {
         case OPERATION_POINT_DRAG -> {
            OperationPointDrag finished = OperationPointInputController.finishOperationPointDrag(minecraft, session);
            OperationPointInputController.finishOperationPointClick(minecraft, session, finished, releasedAtNanos);
            session.operationClick.clear();
            yield true;
         }
         case OPERATION_DRAG -> {
            OperationDragController.finish(minecraft, session, releasedAtNanos);
            session.operationClick.clear();
            yield true;
         }
         case WORKSPACE_GIZMO_DRAG -> {
            session.selectionGestures().finish(session.selectionGestures().capture());
            session.operationClick.clear();
            yield true;
         }
         case WORKSPACE_FACE_DRAG -> {
            session.selectionGestures().finish(session.selectionGestures().capture());
            session.operationClick.clear();
            yield true;
         }
         case GEOMETRY_GIZMO_DRAG -> {
            GeometryDragController.finish(minecraft, session);
            if (button == MouseButtonInputSemantics.LEFT_BUTTON) {
               session.undoPress.cancel();
               session.undoPressCaptured = false;
            }
            yield true;
         }
         case OPERATION_CAPTURE -> {
            session.operationClick.clear();
            yield true;
         }
         case GEOMETRY_CAPTURE -> {
            session.releaseGeometryPointerButton(button);
            yield true;
         }
         case UNDO_PRESS -> finishUndoPress(minecraft, session, releasedAtNanos);
         case NONE -> false;
      };
   }

   static boolean finishGeometryRightRelease(Minecraft minecraft, ClientInputSession session) {
      if (session.geometryGizmoDrag == null || session.geometryGizmoDrag.mouseButton() != MouseButtonInputSemantics.RIGHT_BUTTON) {
         return false;
      }
      GeometryDragController.finish(minecraft, session);
      return true;
   }

   static MouseDragReleaseSemantics.Target target(ClientInputSession session, int action, int button) {
      return MouseDragReleaseSemantics.releaseTarget(action, button, new MouseDragReleaseSemantics.State(
         dragButton(session.operationPointDrag),
         dragButton(session.operationDrag),
         dragButton(session.selectionGestureSnapshot().gizmo()),
         dragButton(session.selectionGestureSnapshot().face()),
         dragButton(session.geometryGizmoDrag),
         session.operationClick.buttonId(),
         session.ownsGeometryPointerButton(button) ? button : -1,
         session.undoPressCaptured
      ));
   }

   private static boolean finishUndoPress(Minecraft minecraft, ClientInputSession session, long releasedAtNanos) {
      boolean shortPress = session.undoPress.release(releasedAtNanos, UNDO_SHORT_PRESS_NANOS);
      session.undoPressCaptured = false;
      if (shortPress && ServerGateway.supports(minecraft, UndoFastPlacePayload.TYPE)) {
         ServerGateway.send(UndoFastPlacePayload.INSTANCE);
      }
      return true;
   }

   private static int dragButton(OperationPointDrag drag) {
      return drag == null ? -1 : drag.mouseButton();
   }

   private static int dragButton(OperationDrag drag) {
      return drag == null ? -1 : drag.mouseButton();
   }

   private static int dragButton(WorkspaceGizmoDrag drag) {
      return drag == null ? -1 : drag.mouseButton();
   }

   private static int dragButton(WorkspaceFaceDrag drag) {
      return drag == null ? -1 : drag.mouseButton();
   }

   private static int dragButton(GeometryGizmoDrag drag) {
      return drag == null ? -1 : drag.mouseButton();
   }
}
