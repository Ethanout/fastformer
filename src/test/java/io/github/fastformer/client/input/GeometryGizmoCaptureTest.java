package io.github.fastformer.client.input;

import io.github.fastformer.core.math.Axis;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.interaction.drag.GeometryGizmoDrag;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryGizmoCaptureTest {
   private GeometryGizmoCapture.Event.Press press(GeometryGizmoCapture capture, int button) {
      var drag = new GeometryGizmoDrag(AxisGizmo.Operation.MOVE, Axis.X,
         Vec3.ZERO, new Vec3(1, 0, 0), 0, 0, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
         AxisGizmo.Direction.POSITIVE, button);
      return capture.press(1, UUID.randomUUID(), OperationCallbackScope.unscoped(), GeometryMode.WALL, drag);
   }

   private void drain(ClientInputSession session, Consumer<KeyboardInputSnapshot> keys,
      Consumer<GeometryGizmoCapture.Event> gizmos) {
      session.drainPhysicalEvents(() -> true, keys, e -> fail(), e -> fail(), e -> fail(),
         e -> fail(), e -> fail(), e -> fail(), e -> fail(), gizmos);
   }

   @Test
   void twoClickPairsInOneTickRetainTheirOwnCaptureAndReleaseRay() {
      var session = new ClientInputSession();
      var capture = session.geometryGizmoCapture;
      var first = press(capture, 0);
      var firstRelease = capture.release(0, new Vec3(3, 4, 5), new Vec3(0, 1, 0), true);
      var second = press(capture, 1);
      var secondRelease = capture.release(1, new Vec3(6, 7, 8), new Vec3(0, 0, 1), false);
      var expected = List.of(first, firstRelease, second, secondRelease);
      expected.forEach(session::postGeometryGizmo);
      var received = new ArrayList<GeometryGizmoCapture.Event>();
      drain(session, e -> fail(), event -> {
         received.add(event);
         switch (event) {
            case GeometryGizmoCapture.Event.Press p -> capture.activate(p, received.size());
            case GeometryGizmoCapture.Event.Release r -> {
               assertTrue(capture.owns(r, received.size() - 1));
               capture.finish(r);
            }
         }
      });
      assertEquals(expected, received);
      assertEquals(new Vec3(3, 4, 5), firstRelease.eye());
      assertEquals(new Vec3(0, 1, 0), firstRelease.view());
      assertTrue(firstRelease.controlDown());
      assertFalse(secondRelease.controlDown());
      assertFalse(capture.captured());
   }

   @Test
   void wrongButtonAndDuplicatePressDoNotReplacePhysicalOwner() {
      var capture = new GeometryGizmoCapture();
      var first = press(capture, 0);
      assertNull(press(capture, 0));
      assertNull(press(capture, 1));
      assertTrue(capture.ownsPhysicalButton(0));
      assertFalse(capture.ownsPhysicalButton(1));
      assertNull(capture.release(1, Vec3.ZERO, Vec3.ZERO, false));
      assertTrue(capture.captured());
      assertSame(first, capture.release(0, Vec3.ZERO, Vec3.ZERO, false).press());
      assertFalse(capture.hasPhysicalPress());
      assertFalse(capture.ownsPhysicalButton(0));
   }

   @Test
   void queuedReleaseKeepsOnlyItsActiveButtonOwnedUntilDispatch() {
      var capture = new GeometryGizmoCapture();
      var first = press(capture, 0);
      capture.activate(first, 11);
      var release = capture.release(0, Vec3.ZERO, Vec3.ZERO, false);
      assertFalse(capture.hasPhysicalPress());
      assertTrue(capture.ownsInteractionButton(0));
      assertFalse(capture.ownsInteractionButton(1));
      var second = press(capture, 1);
      assertTrue(capture.ownsInteractionButton(1));
      capture.finish(release);
      assertFalse(capture.ownsInteractionButton(0));
      assertTrue(capture.ownsPhysicalButton(1));
      assertSame(second, capture.release(1, Vec3.ZERO, Vec3.ZERO, false).press());
   }

   @Test
   void staleReleaseCannotClearANewerActiveCapture() {
      var capture = new GeometryGizmoCapture();
      var first = press(capture, 0);
      var oldRelease = capture.release(0, Vec3.ZERO, Vec3.ZERO, false);
      capture.activate(first, 11);
      var second = press(capture, 0);
      capture.activate(second, 12);
      assertFalse(capture.owns(oldRelease, 12));
      capture.finish(oldRelease);
      assertTrue(capture.owns(12));
      var release = capture.release(0, Vec3.ZERO, Vec3.ZERO, false);
      assertFalse(capture.owns(release, 11));
      assertTrue(capture.owns(release, 12));
   }

   @Test
   void cancellationInvalidatesQueuedPressWithoutInvalidatingNextPress() {
      var capture = new GeometryGizmoCapture();
      var cancelled = press(capture, 0);
      capture.cancel();
      capture.activate(cancelled, 11);
      assertFalse(capture.accepts(cancelled));
      assertFalse(capture.captured());
      var next = press(capture, 0);
      assertTrue(capture.accepts(next));
      capture.activate(next, 12);
      assertTrue(capture.owns(12));
   }

   @Test
   void activeCaptureDoesNotMatchAReplacementDraftInTheSameModeAndScope() {
      var capture = new GeometryGizmoCapture();
      UUID originalDraft = UUID.randomUUID();
      UUID replacementDraft = UUID.randomUUID();
      var drag = new GeometryGizmoDrag(AxisGizmo.Operation.MOVE, Axis.X,
         Vec3.ZERO, new Vec3(1, 0, 0), 0, 0, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
         AxisGizmo.Direction.POSITIVE, 0);
      var press = capture.press(1, originalDraft, OperationCallbackScope.unscoped(), GeometryMode.WALL, drag);

      capture.activate(press, 11);

      assertTrue(capture.matchesActive(originalDraft, OperationCallbackScope.unscoped(), GeometryMode.WALL));
      assertFalse(capture.matchesActive(replacementDraft, OperationCallbackScope.unscoped(), GeometryMode.WALL));
   }

   @Test
   void legacyPreviewWithoutADraftIdentityCannotActivateAGizmoCapture() {
      var capture = new GeometryGizmoCapture();
      var drag = new GeometryGizmoDrag(AxisGizmo.Operation.MOVE, Axis.X,
         Vec3.ZERO, new Vec3(1, 0, 0), 0, 0, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
         AxisGizmo.Direction.POSITIVE, 0);
      var press = capture.press(1, null, OperationCallbackScope.unscoped(), GeometryMode.WALL, drag);

      capture.activate(press, 11);

      assertFalse(capture.matchesActive(UUID.randomUUID(), OperationCallbackScope.unscoped(), GeometryMode.WALL));
      assertNull(capture.activePress());
   }

   @Test
   void queuedCancellationBetweenPressAndReleaseDiscardsRemainingEvents() {
      var session = new ClientInputSession();
      var capture = session.geometryGizmoCapture;
      session.postGeometryGizmo(press(capture, 0));
      session.postKeyboard(new KeyboardInputSnapshot(81, 0, 1, 0, 50, false, false));
      session.postGeometryGizmo(capture.release(0, Vec3.ZERO, Vec3.ZERO, false));
      var received = new ArrayList<GeometryGizmoCapture.Event>();
      drain(session, e -> session.discardPhysicalEvents(), event -> {
         received.add(event);
         capture.activate((GeometryGizmoCapture.Event.Press) event, 11);
      });
      assertEquals(1, received.size());
      assertFalse(capture.captured());
      assertFalse(session.hasQueuedPhysicalInput());
   }

   @Test
   void environmentLossDiscardsQueuedDragAndPhysicalOwnership() {
      var session = new ClientInputSession();
      session.postGeometryGizmo(press(session.geometryGizmoCapture, 0));
      session.drainPhysicalEvents(() -> false, e -> fail(), e -> fail(), e -> fail(), e -> fail(),
         e -> fail(), e -> fail(), e -> fail(), e -> fail(), e -> fail());
      assertFalse(session.geometryGizmoCapture.captured());
      assertFalse(session.hasQueuedPhysicalInput());
   }

   @Test
   void bindingReloadInvalidatesGeometryCommandsWithoutDroppingEarlierKeyboardInput() {
      var session = new ClientInputSession();
      var firstKey = new KeyboardInputSnapshot(65, 0, 1, 0, 1, false, false);
      var secondKey = new KeyboardInputSnapshot(66, 0, 1, 0, 2, false, false);
      session.postKeyboard(firstKey);
      var captured = press(session.geometryGizmoCapture, 4);
      session.postGeometryGizmo(captured);
      session.postGeometryPointer(new GeometryInputController.FallbackUndoPress(GeometryMode.WALL, 1, OperationCallbackScope.unscoped()));
      session.cancelBoundPointerInput();
      session.postKeyboard(secondKey);
      var received = new ArrayList<KeyboardInputSnapshot>();
      drain(session, received::add, event -> fail("Reloaded geometry command must not execute"));
      assertEquals(List.of(firstKey, secondKey), received);
      assertFalse(session.geometryGizmoCapture.accepts(captured));
      assertFalse(session.geometryGizmoCapture.captured());
   }

   @Test
   void disablingTheReleaseActionStillCancelsTheCapturedDragAndItsQueuedPress() {
      var session = new ClientInputSession();
      var captured = press(session.geometryGizmoCapture, 4);
      long token = session.pointerGesture.begin(io.github.fastformer.client.input.state.PointerGestureState.Kind.BUILDING_GEOMETRY);
      session.geometryGizmoDrag = captured.drag().withCapture(token);
      session.geometryGizmoCapture.activate(captured, token);
      session.postGeometryGizmo(captured);
      session.finishGeometryPhysicalRelease(4);
      assertFalse(session.geometryGizmoCapture.captured());
      assertNull(session.geometryGizmoDrag);
      assertEquals(io.github.fastformer.client.input.state.PointerGestureState.Kind.NONE, session.pointerGesture.kind());
      drain(session, event -> fail(), event -> fail("Cancelled physical press must not execute"));
   }

   @Test
   void extraMouseButtonsCanOwnAndReleasePointerCommandsWithoutArrayLimits() {
      var session = new ClientInputSession();
      session.captureGeometryPointerButton(4);
      session.captureGeometryPointerButton(7);
      assertTrue(session.hasGeometryPointerButtons());
      session.finishGeometryPhysicalRelease(4);
      assertFalse(session.ownsGeometryPointerButton(4));
      assertTrue(session.ownsGeometryPointerButton(7));
      session.finishGeometryPhysicalRelease(7);
      assertFalse(session.hasGeometryPointerButtons());
   }
}
