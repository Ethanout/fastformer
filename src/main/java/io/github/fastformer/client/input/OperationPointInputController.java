package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.client.input.drag.OperationPointDragCalculator;
import io.github.fastformer.fastplace.OperationPointDragConstraint;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.network.payload.operation.OperationPointDragPayload;
import io.github.fastformer.network.payload.operation.OperationRemovePointPayload;
import io.github.fastformer.network.payload.geometry.ClosePathPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

final class OperationPointInputController {
   private static final long OPERATION_POINT_SHORT_PRESS_NANOS = 250_000_000L;
   private static final long OPERATION_POINT_DOUBLE_CLICK_NANOS = 350_000_000L;

   private OperationPointInputController() { }

   static boolean beginOperationPointDrag(Minecraft minecraft, ClientInputSession session, int mouseButton) {
      if (!ClientOperationController.operationPrism()
         || session.operationDrag != null
         || session.operationPointDrag != null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         return false;
      }
      int pointIndex = FastPlaceClientPreview.operationPointUnderCrosshairIndex();
      Vec3 center = FastPlaceClientPreview.operationPointCenter(pointIndex);
      if (pointIndex < 0 || center == null) {
         return false;
      }
      BlockPos initialPoint = BlockPos.containing(center);
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      Vec3 axisBaselines = new Vec3(
         OperationPointDragCalculator.axisOffset(center, eye, view, 0),
         OperationPointDragCalculator.axisOffset(center, eye, view, 1),
         OperationPointDragCalculator.axisOffset(center, eye, view, 2)
      );
      SelectionPrism.GridPlane plane = FastPlaceClientPreview.operationPointGridPlane(pointIndex);
      SelectionPrism.GridLine line = FastPlaceClientPreview.operationPointGridLine(pointIndex);
      Vec3 planeHit = plane == null ? null : plane.rayIntersection(eye, view);
      Vec3 planeGrabOffset = planeHit == null ? Vec3.ZERO : center.subtract(planeHit);
      double lineGrabBaseline = line == null ? 0.0 : line.rayOffset(eye, view) - line.offset(initialPoint);
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      session.operationPointDrag = new OperationPointDrag(
         pointIndex,
         mouseButton,
         initialPoint,
         initialPoint,
         plane,
         planeGrabOffset,
         line,
         lineGrabBaseline,
         axisBaselines,
         line != null && plane == null
            ? OperationPointDragConstraint.LINE
            : plane != null ? OperationPointDragConstraint.PLANE : OperationPointDragConstraint.FREE,
         System.nanoTime(), session.pointerGestureToken
      );
      PacketDistributor.sendToServer(
         new OperationPointDragPayload(pointIndex, initialPoint, session.operationPointDrag.constraint(), false),
         new CustomPacketPayload[0]
      );
      return true;
   }

   static void updateOperationPointDrag(Minecraft minecraft, ClientInputSession session) {
      OperationPointDrag drag = session.operationPointDrag;
      if (!acceptsCapture(session, drag)) return;
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      OperationPointDragConstraint constraint = OperationPointDragCalculator.selectConstraint(drag, eye, view);
      if (constraint != drag.constraint()) {
         drag = OperationPointDragCalculator.rebase(drag, constraint, eye, view);
         session.operationPointDrag = drag;
      }
      BlockPos desired = constraint == OperationPointDragConstraint.LINE
         ? OperationPointDragCalculator.lineTarget(drag, eye, view)
         : OperationPointDragCalculator.planeTarget(drag, eye, view);
      if (desired == null) {
         return;
      }
      BlockPos target = OperationPointDragCalculator.nextTarget(drag.sentTarget(), desired, drag, constraint);
      if (target.equals(drag.sentTarget())
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         session.operationPointDrag = drag.withConstraint(constraint);
         return;
      }
      PacketDistributor.sendToServer(
         new OperationPointDragPayload(drag.pointIndex(), target, constraint, false), new CustomPacketPayload[0]
      );
      session.operationPointDrag = drag.withSentTarget(target).withConstraint(constraint);
   }

   static OperationPointDrag finishOperationPointDrag(Minecraft minecraft, ClientInputSession session) {
      OperationPointDrag finished = session.operationPointDrag;
      if (!acceptsCapture(session, finished)) return null;
      if (NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         PacketDistributor.sendToServer(
            new OperationPointDragPayload(
               finished.pointIndex(), finished.sentTarget(), finished.constraint(), true
            ),
            new CustomPacketPayload[0]
         );
      }
      clearCapture(session, finished);
      return finished;
   }

   private static boolean acceptsCapture(ClientInputSession session, OperationPointDrag drag) {
      if (drag == null) return false;
      if (session.pointerGesture.owns(drag.captureToken(), PointerGestureState.Kind.OPERATION_POINT)
         && FastPlaceClientPreview.operationActive() && !ClientOperationController.operationSelectionConfirmed()) return true;
      clearCapture(session, drag);
      return false;
   }

   private static void clearCapture(ClientInputSession session, OperationPointDrag drag) {
      session.operationPointDrag = null;
      session.pointerGesture.finish(drag.captureToken());
      if (session.pointerGestureToken == drag.captureToken()) session.pointerGestureToken = 0L;
   }

   static void finishOperationPointClick(Minecraft minecraft, ClientInputSession session, OperationPointDrag finished, long now) {
      boolean shortUnmovedClick = finished != null
         && now - finished.pressedAt() <= OPERATION_POINT_SHORT_PRESS_NANOS
         && finished.sentTarget().equals(finished.initialPoint());
      if (finished == null || !shortUnmovedClick) {
         session.lastOperationPointLeftClickAt = 0L;
         session.lastOperationPointLeftClickIndex = -1;
         session.lastOperationPointRightClickAt = 0L;
         session.lastOperationPointRightClickIndex = -1;
         return;
      }
      if (finished.pointIndex() == 0
         && FastPlaceClientPreview.operationPrismBaseOpen()
         && FastPlaceClientPreview.operationPointCount() >= 3
         && NetworkRegistry.hasChannel(minecraft.getConnection(), ClosePathPayload.TYPE.id())) {
         PacketDistributor.sendToServer(ClosePathPayload.INSTANCE, new CustomPacketPayload[0]);
         session.lastOperationPointRightClickAt = 0L;
         session.lastOperationPointRightClickIndex = -1;
         session.lastOperationPointLeftClickAt = 0L;
         session.lastOperationPointLeftClickIndex = -1;
         session.pathClose.reset();
         return;
      }
      if (finished.mouseButton() == 1) {
         boolean doubleClick = finished.pointIndex() == session.lastOperationPointRightClickIndex
            && now - session.lastOperationPointRightClickAt <= OPERATION_POINT_DOUBLE_CLICK_NANOS;
         if (doubleClick
            && FastPlaceClientPreview.operationPrismBaseOpen()
            && FastPlaceClientPreview.operationPointCount() >= 3
            && NetworkRegistry.hasChannel(minecraft.getConnection(), ClosePathPayload.TYPE.id())) {
            PacketDistributor.sendToServer(ClosePathPayload.INSTANCE, new CustomPacketPayload[0]);
            session.lastOperationPointRightClickAt = 0L;
            session.lastOperationPointRightClickIndex = -1;
            session.pathClose.reset();
         } else {
            session.lastOperationPointRightClickAt = now;
            session.lastOperationPointRightClickIndex = finished.pointIndex();
         }
         session.lastOperationPointLeftClickAt = 0L;
         session.lastOperationPointLeftClickIndex = -1;
         return;
      }
      session.lastOperationPointRightClickAt = 0L;
      session.lastOperationPointRightClickIndex = -1;
      boolean doubleClick = finished.pointIndex() == session.lastOperationPointLeftClickIndex
         && now - session.lastOperationPointLeftClickAt <= OPERATION_POINT_DOUBLE_CLICK_NANOS;
      if (doubleClick) {
         sendOperationPointRemoval(minecraft, session, finished.pointIndex());
         session.lastOperationPointLeftClickAt = 0L;
         session.lastOperationPointLeftClickIndex = -1;
      } else {
         session.lastOperationPointLeftClickAt = now;
         session.lastOperationPointLeftClickIndex = finished.pointIndex();
      }
   }

   static void resetOperationPointClicks(ClientInputSession session) {
      session.lastOperationPointLeftClickAt = 0L;
      session.lastOperationPointLeftClickIndex = -1;
      session.lastOperationPointRightClickAt = 0L;
      session.lastOperationPointRightClickIndex = -1;
   }

   static boolean sendOperationPointRemoval(Minecraft minecraft, ClientInputSession session, int index) {
      if (index < 0 || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationRemovePointPayload.TYPE.id())) {
         return false;
      }
      PacketDistributor.sendToServer(new OperationRemovePointPayload(index), new CustomPacketPayload[0]);
      return true;
   }
}
