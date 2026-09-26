package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.client.input.drag.OperationPointDragCalculator;
import io.github.fastformer.fastplace.selection.OperationPointDragConstraint;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.network.payload.operation.OperationPointDragPayload;
import io.github.fastformer.network.payload.operation.OperationPointClickPayload;
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

   static java.util.Optional<OperationPointDragPress> capturePress(
      Minecraft minecraft, ClientInputSession session, int mouseButton, long occurredAtNanos
   ) {
      if (!ClientOperationController.operationPrism()
         || session.operationDrag != null || session.operationPointDrag != null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         return java.util.Optional.empty();
      }
      int pointIndex = FastPlaceClientPreview.operationPointUnderCrosshairIndex();
      Vec3 center = FastPlaceClientPreview.operationPointCenter(pointIndex);
      if (pointIndex < 0 || center == null) return java.util.Optional.empty();
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
      return java.util.Optional.of(new OperationPointDragPress(
         1L, mouseButton, occurredAtNanos, ClientOperationController.remoteSelectionIdentity(),
         ClientOperationController.remoteSelectionRevision(), ClientOperationController.remoteSelectionCallbackScope(),
         pointIndex, initialPoint, plane, planeHit == null ? Vec3.ZERO : center.subtract(planeHit), line,
         line == null ? 0.0 : line.rayOffset(eye, view) - line.offset(initialPoint), axisBaselines,
         line != null && plane == null ? OperationPointDragConstraint.LINE
            : plane != null ? OperationPointDragConstraint.PLANE : OperationPointDragConstraint.FREE,
         eye, view
      ));
   }

   static void dispatchPress(Minecraft minecraft, ClientInputSession session, OperationPointDragPress press) {
      if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) != ClientInputStateMachine.Dispatch.OPERATION
         || session.operationDrag != null || session.operationPointDrag != null
         || !ClientOperationController.operationPrism()
         || press.revision() != ClientOperationController.remoteSelectionRevision()
         || !press.callbackScope().equals(ClientOperationController.remoteSelectionCallbackScope())
         || !java.util.Objects.equals(press.selection(), ClientOperationController.remoteSelectionIdentity())) {
         return;
      }
      session.clickGestureToken = session.routing.beginGesture(press.button());
      if (beginOperationPointDrag(minecraft, session, press)) {
         session.operationPointPointer.dispatched(press.identity(), session.clickGestureToken, session.pointerGestureToken);
      } else if (session.routing.finishGesture(press.button(), session.clickGestureToken)) {
         session.clickGestureToken = 0L;
      }
   }

   static void dispatch(Minecraft minecraft, ClientInputSession session, OperationPointDragEvent event) {
      switch (event) {
         case OperationPointDragEvent.Press press -> dispatchPress(minecraft, session, press.snapshot());
         case OperationPointDragEvent.Cancel cancel -> cancelQueuedPress(session, cancel.identity());
         case OperationPointDragEvent.Release release -> finishQueuedPress(minecraft, session, release);
      }
   }

   private static void cancelQueuedPress(ClientInputSession session, long identity) {
      var dispatched = session.operationPointPointer.take(identity);
      if (dispatched == null) return;
      OperationPointDrag drag = session.operationPointDrag;
      if (drag == null || drag.captureToken() != dispatched.pointerToken()
         || session.pointerGestureToken != dispatched.pointerToken()) return;
      if (session.clickGestureToken == dispatched.clickToken()) {
         session.routing.finishGesture(session.operationPointDrag == null ? -1 : session.operationPointDrag.mouseButton(), dispatched.clickToken());
         session.clickGestureToken = 0L;
      }
      cancel(session);
   }

   private static void finishQueuedPress(Minecraft minecraft, ClientInputSession session, OperationPointDragEvent.Release release) {
      var dispatched = session.operationPointPointer.take(release.identity());
      if (dispatched == null || dispatched.clickToken() != session.clickGestureToken
         || dispatched.pointerToken() != session.pointerGestureToken
         || !session.routing.finishGesture(release.button(), dispatched.clickToken())) return;
      session.clickGestureToken = 0L;
      OperationPointDrag finished = finishOperationPointDrag(minecraft, session, release.eye(), release.view());
      finishOperationPointClick(minecraft, session, finished, release.occurredAtNanos());
   }

   private static boolean beginOperationPointDrag(Minecraft minecraft, ClientInputSession session, OperationPointDragPress press) {
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      session.operationPointDrag = new OperationPointDrag(
         press.pointIndex(), press.button(), press.initialPoint(), press.initialPoint(), press.plane(), press.planeGrabOffset(),
         press.line(), press.lineGrabBaseline(), press.axisBaselines(), press.constraint(), press.occurredAtNanos(),
         session.pointerGestureToken, press.revision(), press.callbackScope()
      );
      PacketDistributor.sendToServer(
         new OperationPointDragPayload(session.pointerGestureToken, press.revision(), press.callbackScope(),
            press.pointIndex(), press.initialPoint(), session.operationPointDrag.constraint(), false),
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
         new OperationPointDragPayload(drag.captureToken(), drag.revision(), drag.callbackScope(),
            drag.pointIndex(), target, constraint, false), new CustomPacketPayload[0]
      );
      session.operationPointDrag = drag.withSentTarget(target).withConstraint(constraint);
   }

   static OperationPointDrag finishOperationPointDrag(Minecraft minecraft, ClientInputSession session) {
      if (!acceptsCapture(session, session.operationPointDrag)) return null;
      return finishOperationPointDrag(minecraft, session, minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F));
   }

   static OperationPointDrag finishOperationPointDrag(Minecraft minecraft, ClientInputSession session, Vec3 eye, Vec3 view) {
      OperationPointDrag finished = session.operationPointDrag;
      if (!acceptsCapture(session, finished)) return null;
      OperationPointDragConstraint constraint = OperationPointDragCalculator.selectConstraint(finished, eye, view);
      BlockPos desired = constraint == OperationPointDragConstraint.LINE
         ? OperationPointDragCalculator.lineTarget(finished, eye, view)
         : OperationPointDragCalculator.planeTarget(finished, eye, view);
      if (desired != null) {
         finished = finished.withSentTarget(OperationPointDragCalculator.nextTarget(
            finished.sentTarget(), desired, finished, constraint
         )).withConstraint(constraint);
      }
      if (NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointDragPayload.TYPE.id())) {
         PacketDistributor.sendToServer(
            new OperationPointDragPayload(
               finished.captureToken(), finished.revision(), finished.callbackScope(),
               finished.pointIndex(), finished.sentTarget(), finished.constraint(), true
            ),
            new CustomPacketPayload[0]
         );
      }
      clearCapture(session, finished);
      return finished;
   }

   static void cancel(ClientInputSession session) {
      OperationPointDrag drag = session.operationPointDrag;
      if (drag != null) clearCapture(session, drag);
      resetOperationPointClicks(session);
   }

   private static boolean acceptsCapture(ClientInputSession session, OperationPointDrag drag) {
      if (drag == null) return false;
      if (session.pointerGesture.owns(drag.captureToken(), PointerGestureState.Kind.OPERATION_POINT)
         && drag.callbackScope().equals(ClientOperationController.remoteSelectionCallbackScope())
         && ClientOperationController.operationPrism()
         && FastPlaceClientPreview.operationActive() && !ClientOperationController.operationSelectionConfirmed()) return true;
      clearCapture(session, drag);
      return false;
   }

   private static void clearCapture(ClientInputSession session, OperationPointDrag drag) {
      if (session.operationPointDrag == drag) session.operationPointDrag = null;
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
         && sendPointClick(minecraft, finished, OperationPointClickPayload.Action.CLOSE)) {
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
            && sendPointClick(minecraft, finished, OperationPointClickPayload.Action.CLOSE)) {
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
         sendOperationPointRemoval(minecraft, finished);
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

   static boolean sendOperationPointRemoval(Minecraft minecraft, OperationPointDrag drag) {
      if (drag == null) {
         return false;
      }
      return sendPointClick(minecraft, drag, OperationPointClickPayload.Action.REMOVE);
   }

   private static boolean sendPointClick(Minecraft minecraft, OperationPointDrag drag, OperationPointClickPayload.Action action) {
      if (!NetworkRegistry.hasChannel(minecraft.getConnection(), OperationPointClickPayload.TYPE.id())) return false;
      PacketDistributor.sendToServer(new OperationPointClickPayload(
         drag.captureToken(), drag.revision(), drag.callbackScope(), action, drag.pointIndex()
      ), new CustomPacketPayload[0]);
      return true;
   }
}
