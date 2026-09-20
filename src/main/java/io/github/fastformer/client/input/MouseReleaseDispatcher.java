package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.mouse.MouseDragReleaseSemantics;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import io.github.fastformer.network.payload.placement.UndoFastPlacePayload;

final class MouseReleaseDispatcher {
   private static final long UNDO_SHORT_PRESS_NANOS = 250_000_000L;

   private MouseReleaseDispatcher() { }

   static boolean finish(Minecraft minecraft, ClientInputSession session, int action, int button, long releasedAtNanos) {
      return switch (target(session, action, button)) {
         case OPERATION_POINT_DRAG -> {
            OperationPointDrag finished = OperationPointInputController.finishOperationPointDrag(minecraft, session);
            OperationPointInputController.finishOperationPointClick(minecraft, session, finished, releasedAtNanos);
            session.operationClickCapturedButton = -1;
            yield true;
         }
         case OPERATION_DRAG -> {
            OperationDragController.finish(minecraft, session, releasedAtNanos);
            session.operationClickCapturedButton = -1;
            yield true;
         }
         case WORKSPACE_GIZMO_DRAG -> {
            SelectionGestureController.finishGizmo(session);
            session.operationClickCapturedButton = -1;
            yield true;
         }
         case WORKSPACE_FACE_DRAG -> {
            SelectionGestureController.finishFace(session);
            session.operationClickCapturedButton = -1;
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
            session.operationClickCapturedButton = -1;
            yield true;
         }
         case GEOMETRY_CAPTURE -> {
            session.geometryClickCapturedButton = -1;
            yield true;
         }
         case UNDO_PRESS -> finishUndoPress(minecraft, session, releasedAtNanos);
         case NONE -> false;
      };
   }

   static MouseDragReleaseSemantics.Target target(ClientInputSession session, int action, int button) {
      return MouseDragReleaseSemantics.releaseTarget(action, button, new MouseDragReleaseSemantics.State(
         dragButton(session.operationPointDrag),
         dragButton(session.operationDrag),
         dragButton(ClientOperationController.selectionGestures().gizmo()),
         dragButton(ClientOperationController.selectionGestures().face()),
         dragButton(session.geometryGizmoDrag),
         session.operationClickCapturedButton,
         session.geometryClickCapturedButton,
         session.undoPressCaptured
      ));
   }

   private static boolean finishUndoPress(Minecraft minecraft, ClientInputSession session, long releasedAtNanos) {
      boolean shortPress = session.undoPress.release(releasedAtNanos, UNDO_SHORT_PRESS_NANOS);
      session.undoPressCaptured = false;
      if (shortPress && NetworkRegistry.hasChannel(minecraft.getConnection(), UndoFastPlacePayload.TYPE.id())) {
         PacketDistributor.sendToServer(UndoFastPlacePayload.INSTANCE, new CustomPacketPayload[0]);
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
