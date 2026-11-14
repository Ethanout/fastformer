package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.client.input.drag.OperationPointDragCalculator;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.operation.input.OperationPointDragEvent;
import io.github.fastformer.client.operation.input.OperationPointDragPress;
import io.github.fastformer.common.selection.geometry.SelectionPrism;
import io.github.fastformer.common.selection.OperationPointDragConstraint;
import io.github.fastformer.protocol.c2s.operation.OperationPointClickPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointDragPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

final class OperationPointInputController {
   private static final long OPERATION_POINT_SHORT_PRESS_NANOS = 250_000_000L;
   private static final long OPERATION_POINT_DOUBLE_CLICK_NANOS = 350_000_000L;

   private OperationPointInputController() { }

   static java.util.Optional<OperationPointDragPress> capturePress(
      Minecraft minecraft, ClientInputSession session, int mouseButton, long occurredAtNanos
   ) {
      if (!ClientSessionSystem.instance().operations().operationPrism()
         || session.operationDrag != null || session.operationPointDrag != null
         || !ServerGateway.supports(minecraft, OperationPointDragPayload.TYPE)) {
         return java.util.Optional.empty();
      }
      int pointIndex = ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex();
      Vec3 center = ClientSessionSystem.instance().operations().remotePointer().operationPointCenter(pointIndex);
      if (pointIndex < 0 || center == null) return java.util.Optional.empty();
      BlockPos initialPoint = BlockPos.containing(center);
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      Vec3 axisBaselines = new Vec3(
         OperationPointDragCalculator.axisOffset(center, eye, view, 0),
         OperationPointDragCalculator.axisOffset(center, eye, view, 1),
         OperationPointDragCalculator.axisOffset(center, eye, view, 2)
      );
      SelectionPrism.GridPlane plane = ClientSessionSystem.instance().operations().remotePointer().operationPointGridPlane(pointIndex);
      SelectionPrism.GridLine line = ClientSessionSystem.instance().operations().remotePointer().operationPointGridLine(pointIndex);
      Vec3 planeHit = plane == null ? null : plane.rayIntersection(eye, view);
      return java.util.Optional.of(new OperationPointDragPress(
         1L, mouseButton, occurredAtNanos, ClientSessionSystem.instance().operations().remoteSelectionIdentity(),
         ClientSessionSystem.instance().operations().remoteSelectionRevision(), ClientSessionSystem.instance().operations().remoteSelectionCallbackScope(),
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
         || !ClientSessionSystem.instance().operations().operationPrism()
         || press.revision() != ClientSessionSystem.instance().operations().remoteSelectionRevision()
         || !press.callbackScope().equals(ClientSessionSystem.instance().operations().remoteSelectionCallbackScope())
         || !java.util.Objects.equals(press.selection(), ClientSessionSystem.instance().operations().remoteSelectionIdentity())) {
         return;
      }
      session.clickGestureToken = session.routing.beginGesture(press.button());
      if (beginOperationPointDrag(minecraft, session, press)) {
         session.operationPointPointer.dispatched(press.identity(), session.clickGestureToken, session.pointerGesture.activeToken());
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
         || session.pointerGesture.activeToken() != dispatched.pointerToken()) return;
      if (session.clickGestureToken == dispatched.clickToken()) {
         session.routing.finishGesture(session.operationPointDrag == null ? -1 : session.operationPointDrag.mouseButton(), dispatched.clickToken());
         session.clickGestureToken = 0L;
      }
      cancel(session);
   }

   private static void finishQueuedPress(Minecraft minecraft, ClientInputSession session, OperationPointDragEvent.Release release) {
      var dispatched = session.operationPointPointer.take(release.identity());
      if (dispatched == null || dispatched.clickToken() != session.clickGestureToken
         || dispatched.pointerToken() != session.pointerGesture.activeToken()
         || !session.routing.finishGesture(release.button(), dispatched.clickToken())) return;
      session.clickGestureToken = 0L;
      OperationPointDrag finished = finishOperationPointDrag(minecraft, session, release.eye(), release.view());
      finishOperationPointClick(minecraft, session, finished, release.occurredAtNanos());
   }

   private static boolean beginOperationPointDrag(Minecraft minecraft, ClientInputSession session, OperationPointDragPress press) {
      session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      session.operationPointDrag = new OperationPointDrag(
         press.pointIndex(), press.button(), press.initialPoint(), press.initialPoint(), press.plane(), press.planeGrabOffset(),
         press.line(), press.lineGrabBaseline(), press.axisBaselines(), press.constraint(), press.occurredAtNanos(),
         session.pointerGesture.activeToken(), press.revision(), press.callbackScope()
      );
      ServerGateway.send(
         new OperationPointDragPayload(session.pointerGesture.activeToken(), press.revision(), press.callbackScope(),
            press.pointIndex(), press.initialPoint(), session.operationPointDrag.constraint(), false)
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
         || !ServerGateway.supports(minecraft, OperationPointDragPayload.TYPE)) {
         session.operationPointDrag = drag.withConstraint(constraint);
         return;
      }
      ServerGateway.send(
         new OperationPointDragPayload(drag.captureToken(), drag.revision(), drag.callbackScope(),
            drag.pointIndex(), target, constraint, false)
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
      if (ServerGateway.supports(minecraft, OperationPointDragPayload.TYPE)) {
         ServerGateway.send(
            new OperationPointDragPayload(
               finished.captureToken(), finished.revision(), finished.callbackScope(),
               finished.pointIndex(), finished.sentTarget(), finished.constraint(), true
            )
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
         && drag.callbackScope().equals(ClientSessionSystem.instance().operations().remoteSelectionCallbackScope())
         && ClientSessionSystem.instance().operations().operationPrism()
         && ClientSessionSystem.instance().operationActive() && !ClientSessionSystem.instance().operations().operationSelectionConfirmed()) return true;
      clearCapture(session, drag);
      return false;
   }

   private static void clearCapture(ClientInputSession session, OperationPointDrag drag) {
      if (session.operationPointDrag == drag) session.operationPointDrag = null;
      session.pointerGesture.finish(drag.captureToken());
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
         && ClientSessionSystem.instance().operations().remotePointer().operationPrismBaseOpen()
         && ClientSessionSystem.instance().operations().remotePointer().operationPointCount() >= 3
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
            && ClientSessionSystem.instance().operations().remotePointer().operationPrismBaseOpen()
            && ClientSessionSystem.instance().operations().remotePointer().operationPointCount() >= 3
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
      if (!ServerGateway.supports(minecraft, OperationPointClickPayload.TYPE)) return false;
      ServerGateway.send(new OperationPointClickPayload(
         drag.captureToken(), drag.revision(), drag.callbackScope(), action, drag.pointIndex()
      ));
      return true;
   }
}
