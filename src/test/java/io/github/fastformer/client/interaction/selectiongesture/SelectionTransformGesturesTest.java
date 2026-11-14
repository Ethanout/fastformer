package io.github.fastformer.client.interaction.selectiongesture;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.client.interaction.selectiongesture.AabbAdjustDecision.*;

import io.github.fastformer.client.interaction.selectiongesture.AabbAdjustDecision;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.core.math.Axis;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SelectionTransformGesturesTest {
   @Test void independentCapabilitiesNeverShareStateOrCleanup() {
      var first = new Fixture();
      var second = new Fixture();
      first.beginFace(); second.beginFace();
      var secondDrag = second.gestures.face();
      first.gestures.cancel();
      assertFalse(first.activeEdit != null);
      assertSame(secondDrag, second.gestures.snapshot().face());
      assertTrue(second.activeEdit.valid());
   }

   @Test void finishAndCancelSettleTheirLeaseExactlyOnce() {
      var f = new Fixture(); f.beginFace();
      var capture = f.gestures.capture();
      f.gestures.finish(capture); f.gestures.finish(capture); f.gestures.cancel(capture);
      assertEquals(1, f.finished); assertEquals(0, f.cancelled);
      assertEquals(1, f.pointer.finishes); assertFalse(f.activeEdit != null);
      f.beginFace();
      f.gestures.cancel(); f.gestures.cancel();
      assertEquals(1, f.cancelled); assertEquals(2, f.pointer.finishes);
   }

   @Test void lateReleaseAndSourceFailureCannotCancelANewerDrag() {
      var f = new Fixture(); f.decision = CAPTURE_PENDING; f.beginFace();
      var previous = f.gestures.face(); f.gestures.cancel(); f.beginFace();
      var next = f.gestures.face();
      f.gestures.finish(previous.capture()); f.gestures.cancel(previous.capture());
      assertNull(f.gestures.resolveFaceSource(previous, SOURCE_CHANGED));
      assertSame(next, f.gestures.face());
      assertTrue(f.activeEdit.valid());
   }

   @Test void sourceResumeRetainsFrozenBaselinePointerAndEditLease() {
      var f = new Fixture(); f.decision = CAPTURE_PENDING; f.beginFace();
      var waiting = f.gestures.face(); var edit = f.activeEdit;
      assertSame(waiting, f.gestures.resolveFaceSource(waiting, CAPTURE_PENDING));
      var ready = f.gestures.resolveFaceSource(waiting, READY);
      assertFalse(ready.awaitingSource());
      assertSame(waiting.capture(), ready.capture()); assertSame(edit, f.activeEdit);
      assertEquals(waiting.frame(), ready.frame());
      assertEquals(waiting.deferredClick(), ready.deferredClick());
   }

   @Test void releaseDuringSourceWaitPreventsLateResume() {
      var f = new Fixture(); f.decision = CAPTURE_PENDING; f.beginFace();
      var waiting = f.gestures.face(); f.gestures.finish(waiting.capture());
      assertNull(f.gestures.resolveFaceSource(waiting, READY));
      assertFalse(f.gestures.active()); assertFalse(f.activeEdit != null);
   }

   @Test void sourceFailureCancelsAndReportsOnlyItsOwnCapture() {
      var f = new Fixture(); f.decision = CAPTURE_PENDING; f.beginFace();
      assertNull(f.gestures.resolveFaceSource(f.gestures.face(), SOURCE_CHANGED));
      assertEquals(1, f.cancelled); assertEquals(List.of("SOURCE_CHANGED"), f.feedback);
      assertFalse(f.gestures.active()); assertFalse(f.activeEdit != null);
   }

   @Test void ownerChangeCancelsOldEditWithoutFinishingReplacementPointer() {
      var f = new Fixture(); f.beginFace();
      f.owner = UUID.randomUUID();
      long replacement = f.pointer.begin(SelectionGesturePointer.Kind.GIZMO);
      f.gestures.update(Vec3.ZERO, new Vec3(1, 0, 0), false, 200_000_001L);
      assertEquals(1, f.cancelled); assertFalse(f.gestures.active());
      assertTrue(f.pointer.owns(replacement, SelectionGesturePointer.Kind.GIZMO));
   }

   @Test void replacingWorkspaceLeaseNeverCancelsTheNewLease() {
      var f = new Fixture(); f.beginFace();
      var replacement = f.newEdit();
      f.gestures.finish(f.gestures.capture());
      assertSame(replacement, f.activeEdit); assertEquals(1, f.cancelled);
   }

   @Test void faceRetainsIts140MillisecondClickDelay() {
      var f = new Fixture(); f.beginFace();
      var eye = new Vec3(10, 1, 1); var view = new Vec3(-1, 0, 0);
      f.gestures.update(eye, view, false, 140_000_000L);
      assertEquals(0, f.faceUpdates);
      f.gestures.update(eye, view, false, 140_000_001L);
      assertEquals(0, f.gestures.face().deferredClick().steps());
   }

   @Test void deniedAdmissionNeverBorrowsPointerOrEditLease() {
      var f = new Fixture(); f.decision = NO_TARGET;
      assertEquals(NO_TARGET, f.gestures.beginFace(f.press()));
      assertFalse(f.activeEdit != null); assertEquals(0, f.pointer.next);
      f.pendingSubmission = true;
      assertEquals(SUBMISSION_PENDING, f.gestures.beginFace(f.press()));
      assertFalse(f.activeEdit != null);
   }

   @Test void failedFinishCancelsTheEditBeforeReleasingPointer() {
      var f = new Fixture(); f.failFinish = true; f.beginFace();
      assertThrows(IllegalStateException.class, () -> f.gestures.finish(f.gestures.capture()));
      assertEquals(1, f.cancelled); assertEquals(1, f.pointer.finishes);
      assertFalse(f.activeEdit != null); assertFalse(f.gestures.active());
   }

   @Test void failedAcquisitionReleasesExactEditAndPointerWhilePreservingFailure() {
      var f = new Fixture();
      var press = f.press();
      var failure = new IllegalStateException("scene unavailable");
      f.targetsFailure = failure; f.failCancel = true; f.pointer.failFinish = true;
      assertSame(failure, assertThrows(IllegalStateException.class, () -> f.gestures.beginFace(press)));
      assertEquals(2, failure.getSuppressed().length);
      assertFalse(f.activeEdit != null); assertEquals(0, f.pointer.active);
      assertEquals(1, f.cancelled); assertEquals(1, f.pointer.finishes); assertFalse(f.gestures.active());
   }

   @Test void invalidRingNeverBorrowsPointerAndReturnsEditLease() {
      var f = new Fixture();
      var gizmo = new AxisGizmo(Vec3.ZERO, 1.0, 0.1);
      var ring = gizmo.handles().stream().filter(AxisGizmo.Handle::drawsRing).findFirst().orElseThrow();
      var target = new SelectionGesturePress.Gizmo(0, true, gizmo, new AxisGizmo.Hit(ring, Vec3.ZERO, 1.0, 0.1), 0, false);
      assertFalse(f.gestures.beginGizmo(target));
      assertEquals(1, f.cancelled); assertEquals(0, f.pointer.next); assertFalse(f.activeEdit != null);
   }

   @Test void deferredResizeFinishTransfersLeaseUntilTransformCompletes() {
      var f = new Fixture(); f.deferFinish = true; f.beginFace();
      var drag = f.gestures.face(); var edit = f.activeEdit; f.gestures.finish(drag.capture());
      assertFalse(f.gestures.active()); assertSame(edit, f.activeEdit);
      f.gestures.cancel(drag.capture()); assertEquals(0, f.cancelled);
      edit.release(); assertFalse(f.activeEdit != null);
   }

   private static final class Fixture implements SelectionGestureEdits {
      final Pointer pointer = new Pointer();
      final List<String> feedback = new ArrayList<>();
      UUID owner = UUID.randomUUID();
      long targetIdentity = 7;
      AabbAdjustDecision decision = READY;
      int finished, cancelled, faceUpdates;
      boolean deferFinish, failFinish, pendingSubmission, failCancel;
      RuntimeException targetsFailure;
      FakeEdit activeEdit;
      final SelectionTransformGestures gestures = new SelectionTransformGestures(this, pointer,
         key -> { if (key != null) feedback.add(key); });
      void beginFace() { assertEquals(DRAG_STARTED, gestures.beginFace(press())); }
      SelectionGesturePress.Face press() {
         return new SelectionGesturePress.Face(1,
            new SelectionFaceHit(new Vec3(3, 1, 1), new Vec3(1, 0, 0), 4, 0), true, 1, 1, 0);
      }
      FakeEdit newEdit() { return activeEdit = new FakeEdit(); }
      public boolean submissionPending() { return pendingSubmission; }
      public AabbAdjustDecision faceReadiness(int partId) { return decision; }
      public FaceEdit beginFace(int partId) { return newEdit(); }
      public GizmoEdit beginGizmo(int partId, boolean common, boolean control, AxisGizmo.Operation operation, Axis axis) {
         return newEdit();
      }
      public void focus(int partId, boolean control) { }
      public void select(int partId, boolean toggle) { }
      public String failureKey(AabbAdjustDecision value) { return value.name(); }
      final class FakeEdit implements FaceEdit, GizmoEdit {
         final UUID capturedOwner = owner;
         final long identity = targetIdentity;
         public Targets targets() {
            if (targetsFailure != null) throw targetsFailure;
            return new Targets(capturedOwner, Map.of(1, identity));
         }
         public boolean valid() {
            return activeEdit == this && capturedOwner.equals(owner) && identity == targetIdentity;
         }
         public AabbAdjustDecision readiness() { return decision; }
         public void update(int axis, boolean positive, int steps) { faceUpdates++; }
         public boolean repeatsWholeGroup() { return false; }
         public int repeatStride() { return 1; }
         public String update(int direction, int steps, double radians, Vec3 vector, Vec3 pivot) { return null; }
         public boolean finish() {
            finished++;
            if (failFinish) throw new IllegalStateException("failed finish");
            if (!deferFinish) release();
            return true;
         }
         public void cancel() {
            cancelled++; release();
            if (failCancel) throw new IllegalStateException("cancel callback failed");
         }
         void release() { if (activeEdit == this) activeEdit = null; }
      }
   }

   private static final class Pointer implements SelectionGesturePointer {
      long next, active; Kind kind; int finishes; boolean failFinish;
      public long begin(Kind value) { kind = value; return active = ++next; }
      public boolean owns(long token, Kind value) { return token == active && kind == value; }
      public void finish(long token) {
         finishes++; if (token == active) { active = 0; kind = null; }
         if (failFinish) throw new IllegalStateException("pointer release failed");
      }
      public void consumeModifier() { }
   }
}
