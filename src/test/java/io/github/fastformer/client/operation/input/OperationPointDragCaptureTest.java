package io.github.fastformer.client.operation.input;

import io.github.fastformer.common.selection.OperationPointDragConstraint;
import io.github.fastformer.common.selection.OperationSelectionMode;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import org.junit.jupiter.api.Test;

class OperationPointDragCaptureTest {
   @Test void extraPhysicalButtonPreservesTheLogicalRightClickThroughCaptureAndDragRebasing() {
      var original = snapshot(1);
      var press = new OperationPointDragPress(original.identity(), 5, original.occurredAtNanos(), original.selection(),
         original.revision(), original.callbackScope(), original.pointIndex(), original.initialPoint(), original.plane(),
         original.planeGrabOffset(), original.line(), original.lineGrabBaseline(), original.axisBaselines(),
         original.constraint(), original.eye(), original.view(), 1);
      var capture = new OperationPointDragCapture();
      var captured = capture.capture(press);
      assertEquals(5, captured.button()); assertEquals(1, captured.logicalButton());
      assertNull(capture.release(1, 20, captured.eye(), captured.view()));
      assertNotNull(capture.release(5, 20, captured.eye(), captured.view()));
      var drag = new io.github.fastformer.client.input.drag.OperationPointDrag(0, captured.button(), captured.initialPoint(),
         captured.initialPoint(), captured.plane(), captured.planeGrabOffset(), captured.line(), captured.lineGrabBaseline(),
         captured.axisBaselines(), captured.constraint(), captured.occurredAtNanos(), 7L, captured.revision(), captured.callbackScope(), captured.logicalButton());
      assertEquals(1, drag.withConstraint(OperationPointDragConstraint.FREE).withSentTarget(net.minecraft.core.BlockPos.ZERO)
         .withPlaneFrame(null, captured.planeGrabOffset(), captured.axisBaselines(), captured.constraint())
         .withLineFrame(null, 0, captured.constraint()).logicalButton());
   }
   @Test
   void twoClicksCapturedBeforeDispatchKeepTheirOwnReleaseAndView() {
      var capture = new OperationPointDragCapture();
      var first = capture.capture(snapshot(0));
      var firstEye = new net.minecraft.world.phys.Vec3(1, 2, 3);
      var firstRelease = capture.release(0, 20L, firstEye, first.view());
      var second = capture.capture(snapshot(1));
      var secondEye = new net.minecraft.world.phys.Vec3(4, 5, 6);
      var secondRelease = capture.release(1, 30L, secondEye, second.view());

      capture.dispatched(first.identity(), 10L, 11L);
      assertEquals(10L, capture.take(firstRelease.identity()).clickToken());
      capture.dispatched(second.identity(), 20L, 21L);
      assertNull(capture.take(firstRelease.identity()));
      assertEquals(20L, capture.take(secondRelease.identity()).clickToken());
      assertEquals(firstEye, firstRelease.eye());
      assertEquals(secondEye, secondRelease.eye());
      assertEquals(20L, firstRelease.occurredAtNanos());
      assertEquals(30L, secondRelease.occurredAtNanos());
   }

   @Test
   void environmentCleanupInvalidatesBothPhysicalAndDispatchedCapture() {
      var capture = new OperationPointDragCapture();
      var press = capture.capture(snapshot(0));
      capture.dispatched(press.identity(), 10L, 11L);
      capture.clear();
      assertNull(capture.release(0, 20L, press.eye(), press.view()));
      assertNull(capture.take(press.identity()));
      assertNotEquals(press.identity(), capture.capture(snapshot(0)).identity());
   }

   @Test
   void onlyTheCapturedButtonCanReleaseTheQueuedPress() {
      var capture = new OperationPointDragCapture();
      var press = capture.capture(snapshot(0));
      assertNull(capture.release(1, 20L, net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec3.ZERO));
      var release = capture.release(0, 20L, net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec3.ZERO);
      assertNotNull(release);
      assertEquals(press.identity(), release.identity());
      assertNull(capture.release(0, 30L, net.minecraft.world.phys.Vec3.ZERO, net.minecraft.world.phys.Vec3.ZERO));
   }

   @Test
   void replacementReleaseCannotTakeNewDispatch() {
      var capture = new OperationPointDragCapture();
      var old = capture.capture(snapshot(0));
      capture.dispatched(old.identity(), 4L, 5L);
      var replacement = capture.capture(snapshot(0));
      assertNull(capture.take(replacement.identity()));
      assertEquals(4L, capture.take(old.identity()).clickToken());
   }

   private static OperationPointDragPress snapshot(int button) {
      return new OperationPointDragPress(1L, button, 10L,
         new io.github.fastformer.client.session.draft.OperationDraftIdentity(
            io.github.fastformer.common.selection.OperationSelectionMode.PRISM, java.util.List.of(), 0,
            net.minecraft.core.BlockPos.ZERO, net.minecraft.core.BlockPos.ZERO, 0.0, null, null
         ), 1L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped(), 0,
         net.minecraft.core.BlockPos.ZERO, null, net.minecraft.world.phys.Vec3.ZERO, null, 0.0,
         net.minecraft.world.phys.Vec3.ZERO, io.github.fastformer.common.selection.OperationPointDragConstraint.FREE,
         net.minecraft.world.phys.Vec3.ZERO, new net.minecraft.world.phys.Vec3(0.0, 0.0, 1.0));
   }
}
