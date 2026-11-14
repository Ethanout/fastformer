package io.github.fastformer.client.feedback;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.core.math.Axis;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FeedbackFrameTest {
   @Test void repeatedReadsAndCapturesDoNotAdvanceTheSameFrame() {
      var feedback = new InteractionFeedback(axis -> 0xFFFFFFFF);
      feedback.noteGizmo(Axis.X, AxisGizmo.Operation.MOVE, 3, 4, 0);
      var first = feedback.capture(1, 100, BuildingPreviewSnapshot.inactive(), null, "X:MOVE", null);
      assertSame(first, feedback.frame());
      assertSame(first, feedback.capture(1, 2_000_000_000L, BuildingPreviewSnapshot.inactive(), null, null, null));
      assertEquals(255, first.gizmoAlpha());
      var next = feedback.capture(2, 2_000_000_000L, BuildingPreviewSnapshot.inactive(), null, null, null);
      assertEquals(0, next.gizmoAlpha());
      assertEquals(255, first.gizmoAlpha());
   }

   @Test void clearDiscardsFrozenFeedbackAndPermitsReusingAFrameIdentifier() {
      var feedback = new InteractionFeedback(axis -> 0xFFFFFFFF);
      feedback.noteGizmo(Axis.Y, AxisGizmo.Operation.SCALE, 2, 1, 0);
      feedback.capture(1, 100, BuildingPreviewSnapshot.inactive(), null, null, null);
      feedback.clear();
      assertSame(FeedbackFrame.EMPTY, feedback.frame());
      assertNull(feedback.capture(1, 100, BuildingPreviewSnapshot.inactive(), null, null, null).lastAxis());
   }

   @Test void faceFeedbackResetsBothChannelsAcrossWorldsWithTheSamePartIdentifier() {
      var feedback = new SelectionFaceFeedback(100);
      var first = face(0, 0);
      var next = face(10, 20);
      feedback.capture(1, 0, "first-world", first, first, 1);
      feedback.capture(2, 20, "first-world", next, next, 1);
      var frame = feedback.capture(3, 30, "second-world", next, next, 1);
      assertEquals(next, frame.operation());
      assertEquals(next, frame.workspace());
      feedback.clear();
      assertSame(SelectionFaceFeedback.Frame.EMPTY, feedback.frame());
      assertEquals(first, feedback.capture(3, 30, "second-world", first, first, 1).workspace());
   }

   @Test void faceGuideCapturesOnceAndNeverInterpolatesAlongItsNormal() {
      var feedback = new SelectionFaceFeedback(100);
      feedback.capture(1, 0, "world", face(0, 0), null, null);
      var frame = feedback.capture(2, 20, "world", face(10, 20), null, null);
      assertEquals(10, frame.operation().point().x);
      assertEquals(0, frame.operation().point().y);
      assertSame(frame, feedback.capture(2, 90, "world", face(10, 20), null, null));
   }

   @Test void absenceClearsBothGuidesBeforeTheSameScopeReturns() {
      var feedback = new SelectionFaceFeedback(100);
      feedback.capture(1, 0, "world", face(0, 0), face(0, 0), 4);
      var hidden = feedback.capture(2, 20, "world", null, null, null);
      assertNull(hidden.operation());
      assertNull(hidden.workspace());
      var returned = feedback.capture(3, 30, "world", face(0, 20), face(0, 20), 4);
      assertEquals(face(0, 20), returned.operation());
      assertEquals(face(0, 20), returned.workspace());
   }

   private static SelectionFaceFeedback.Face face(double x, double y) {
      return new SelectionFaceFeedback.Face(new Vec3(x, y, 0), new Vec3(1, 0, 0), 2, 0);
   }
}
