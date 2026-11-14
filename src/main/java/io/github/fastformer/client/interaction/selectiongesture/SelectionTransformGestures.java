package io.github.fastformer.client.interaction.selectiongesture;

import io.github.fastformer.client.interaction.drag.DeferredDragClick;
import io.github.fastformer.client.interaction.drag.DragAxisFrame;
import io.github.fastformer.client.interaction.drag.GeometryGizmoDrag;
import io.github.fastformer.client.interaction.drag.GizmoDragCalculator;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.math.RayAxisProjection;
import io.github.fastformer.common.math.RepeatStepQuantizer;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;

/** Owns local transform state and borrowed edit/pointer leases, independent of their runtime owners. */
public final class SelectionTransformGestures {
   private static final long FACE_SHORT_PRESS_NANOS = 140_000_000L;
   private final SelectionGestureEdits edits;
   private final SelectionGesturePointer pointer;
   private final Consumer<String> feedback;
   private SelectionDrag current;

   public SelectionTransformGestures(SelectionGestureEdits edits, SelectionGesturePointer pointer, Consumer<String> feedback) {
      this.edits = Objects.requireNonNull(edits, "edits");
      this.pointer = Objects.requireNonNull(pointer, "pointer");
      this.feedback = Objects.requireNonNull(feedback, "feedback");
   }
   public SelectionGestureSnapshot snapshot() { return new SelectionGestureSnapshot(face(), gizmo()); }
   public boolean active() { return current != null; }
   public SelectionDragCapture capture() { return current == null ? null : current.capture(); }
   public WorkspaceFaceDrag face() { return current instanceof SelectionDrag.Face drag ? drag.view() : null; }
   public WorkspaceGizmoDrag gizmo() { return current instanceof SelectionDrag.Gizmo drag ? drag.view() : null; }

   public void press(SelectionGesturePress press) {
      if (active()) return;
      switch (press) {
         case SelectionGesturePress.Gizmo gizmo -> beginGizmo(gizmo);
         case SelectionGesturePress.Face face -> {
            edits.focus(face.partId(), false);
            if (face.adjustable()) feedback.accept(edits.failureKey(beginFace(face)));
         }
         case SelectionGesturePress.Part part -> edits.select(part.partId(), part.control());
      }
   }

   public boolean beginGizmo(SelectionGesturePress.Gizmo press) {
      if (active() || edits.submissionPending()) return false;
      var handle = press.hit().handle();
      var edit = edits.beginGizmo(press.partId(), press.common(), press.control(), handle.operation(), handle.axis());
      if (edit == null) return false;
      long token = 0L;
      try {
         Vec3 axis = press.gizmo().axisVector(handle.axis());
         if (handle.direction() == AxisGizmo.Direction.NEGATIVE) axis = axis.scale(-1.0);
         Vec3 radial = handle.drawsRing() ? press.hit().point().subtract(press.gizmo().center()) : Vec3.ZERO;
         if (handle.drawsRing() && radial.lengthSqr() < 1.0E-7) { edit.cancel(); return false; }
         token = pointer.begin(SelectionGesturePointer.Kind.GIZMO);
         var capture = capture(edit, press.button(), token);
         var view = new WorkspaceGizmoDrag(press.partId(), press.common(), handle.operation(), handle.axis(),
            press.hit().point(), axis, 0, press.gizmo().center(),
            handle.drawsRing() ? radial.normalize() : Vec3.ZERO,
            handle.drawsRing() ? GizmoDragCalculator.rotationTangent(axis, radial.normalize()) : Vec3.ZERO,
            handle.direction(), capture, 0.0, press.control());
         current = new SelectionDrag.Gizmo(view, edit);
         return true;
      } catch (RuntimeException failure) { discardAcquisition(edit, token, failure); throw failure; }
   }

   AabbAdjustDecision beginFace(SelectionGesturePress.Face press) {
      if (active()) return AabbAdjustDecision.EDIT_UNAVAILABLE;
      if (edits.submissionPending()) return AabbAdjustDecision.SUBMISSION_PENDING;
      if (!press.adjustable()) return AabbAdjustDecision.NO_TARGET;
      var decision = edits.faceReadiness(press.partId());
      if (!decision.ready() && decision != AabbAdjustDecision.CAPTURE_PENDING) return decision;
      var edit = edits.beginFace(press.partId());
      if (edit == null) return AabbAdjustDecision.EDIT_UNAVAILABLE;
      long token = 0L;
      try {
         var hit = press.hit();
         Vec3 axis = switch (hit.axis()) {
            case 0 -> new Vec3(1, 0, 0); case 1 -> new Vec3(0, 1, 0);
            case 2 -> new Vec3(0, 0, 1); default -> Vec3.ZERO;
         };
         token = pointer.begin(SelectionGesturePointer.Kind.FACE);
         var view = new WorkspaceFaceDrag(press.partId(), hit.axis(), hit.normal().dot(axis) > 0.0,
            DragAxisFrame.start(hit.point(), false), hit.normal(), 0, capture(edit, press.button(), token),
            DeferredDragClick.start(press.pressedAtNanos(), press.shortPressSteps()), hit, !decision.ready());
         current = new SelectionDrag.Face(view, edit);
         return AabbAdjustDecision.DRAG_STARTED;
      } catch (RuntimeException failure) { discardAcquisition(edit, token, failure); throw failure; }
   }

   private static SelectionDragCapture capture(SelectionGestureEdits.Edit edit, int button, long token) {
      var targets = edit.targets();
      return new SelectionDragCapture(targets.owner(), targets.identities(), button, token);
   }

   public void update(Vec3 eye, Vec3 view, boolean control, long now) {
      var drag = current;
      if (drag == null) return;
      if (!owns(drag)) { cancel(drag.capture()); return; }
      switch (drag) {
         case SelectionDrag.Face face -> updateFace(face, eye, view, now);
         case SelectionDrag.Gizmo gizmo -> updateGizmo(gizmo, eye, view, control);
      }
   }
   private boolean owns(SelectionDrag drag) {
      var kind = drag instanceof SelectionDrag.Face ? SelectionGesturePointer.Kind.FACE : SelectionGesturePointer.Kind.GIZMO;
      return pointer.owns(drag.capture().gestureToken(), kind) && drag.edit().valid();
   }

   private void updateFace(SelectionDrag.Face state, Vec3 eye, Vec3 view, long now) {
      var drag = state.view();
      if (drag.awaitingSource()) {
         drag = resolveFaceSource(drag, state.edit().readiness());
         if (drag == null || drag.awaitingSource()) return;
         state = (SelectionDrag.Face)current;
      }
      if (drag.deferredClick().awaitingRelease(now, FACE_SHORT_PRESS_NANOS)) return;
      if (drag.deferredClick().steps() != 0) drag = drag.withDeferredClick(drag.deferredClick().cancel());
      Vec3 point = RayAxisProjection.closestPoint(drag.frame().origin(), drag.normal(), eye, view);
      int steps = drag.frame().project(point, drag.normal());
      if (steps != drag.sentSteps()) {
         state.edit().update(drag.axis(), drag.positive(), steps);
         drag = drag.withSentSteps(steps);
      }
      replace(state, new SelectionDrag.Face(drag, state.edit()));
   }
   WorkspaceFaceDrag resolveFaceSource(WorkspaceFaceDrag expected, AabbAdjustDecision decision) {
      if (!(current instanceof SelectionDrag.Face state) || state.view() != expected) return null;
      if (!owns(state)) { cancel(state.capture()); return null; }
      if (decision == AabbAdjustDecision.CAPTURE_PENDING) return expected;
      if (!decision.ready()) { cancel(state.capture()); feedback.accept(edits.failureKey(decision)); return null; }
      var ready = expected.sourceReady();
      return replace(state, new SelectionDrag.Face(ready, state.edit())) ? ready : null;
   }

   private void updateGizmo(SelectionDrag.Gizmo state, Vec3 eye, Vec3 view, boolean control) {
      var drag = state.view();
      var projection = new GeometryGizmoDrag(drag.operation(), drag.axis(), drag.origin(), drag.axisVector(),
         drag.sentSteps(), 0.0, drag.center(), drag.startRadial(), drag.startTangent(), drag.direction(), drag.mouseButton());
      int steps; double radians = Double.NaN;
      if (drag.operation() == AxisGizmo.Operation.ROTATE) {
         radians = SelectionRotationAngles.preview(GizmoDragCalculator.geometryRotationSteps(projection, eye, view), control);
         steps = GizmoDragCalculator.rotationSteps(radians);
      } else {
         double offset = GizmoDragCalculator.operationEndpointOffset(projection, eye, view);
         steps = state.edit().repeatsWholeGroup()
            ? RepeatStepQuantizer.copiesForOffset(offset, state.edit().repeatStride()) : (int)Math.round(offset);
         steps = Math.clamp(steps, -128, 128);
      }
      if (steps == drag.sentSteps() && (drag.operation() != AxisGizmo.Operation.ROTATE || control == drag.freeRotation())) return;
      int direction = drag.direction() == AxisGizmo.Direction.NEGATIVE ? -1 : 1;
      feedback.accept(state.edit().update(direction, steps, radians, drag.axisVector(), drag.center()));
      var next = drag.operation() == AxisGizmo.Operation.ROTATE
         ? drag.withRotation(steps, radians, control) : drag.withSentSteps(steps);
      replace(state, new SelectionDrag.Gizmo(next, state.edit()));
      pointer.consumeModifier();
   }

   public void finish(SelectionDragCapture capture) {
      var drag = capturedBy(capture);
      if (drag == null) return;
      if (!owns(drag)) { cancel(capture); return; }
      RuntimeException failure = null;
      try {
         if (drag instanceof SelectionDrag.Gizmo state && state.view().operation() == AxisGizmo.Operation.ROTATE) {
            var gizmo = state.view();
            double radians = SelectionRotationAngles.finish(gizmo.rotationRadians(), gizmo.freeRotation());
            feedback.accept(state.edit().update(1, GizmoDragCalculator.rotationSteps(radians), radians, gizmo.axisVector(), gizmo.center()));
         }
         // Deferred resize owns its lease after pointer release until its asynchronous write completes.
         drag.edit().finish();
         if (drag instanceof SelectionDrag.Gizmo) pointer.consumeModifier();
      } catch (RuntimeException thrown) {
         failure = thrown;
         try { drag.edit().cancel(); } catch (RuntimeException cleanup) { suppress(failure, cleanup); }
      }
      failure = release(drag, failure);
      if (failure != null) throw failure;
   }
   public void cancel() { if (current != null) cancel(current.capture()); }
   public void cancel(SelectionDragCapture capture) {
      var drag = capturedBy(capture);
      if (drag == null) return;
      RuntimeException failure = null;
      try { drag.edit().cancel(); } catch (RuntimeException thrown) { failure = thrown; }
      failure = release(drag, failure);
      if (failure != null) throw failure;
   }
   private SelectionDrag capturedBy(SelectionDragCapture capture) {
      return capture != null && current != null && current.capture() == capture ? current : null;
   }
   private RuntimeException release(SelectionDrag drag, RuntimeException failure) {
      try { pointer.finish(drag.capture().gestureToken()); }
      catch (RuntimeException cleanup) {
         if (failure == null) failure = cleanup; else suppress(failure, cleanup);
      } finally { if (current == drag) current = null; }
      return failure;
   }
   private boolean replace(SelectionDrag expected, SelectionDrag next) {
      if (current != expected || expected.capture() != next.capture() || expected.edit() != next.edit()) return false;
      current = next; return true;
   }
   private void discardAcquisition(SelectionGestureEdits.Edit edit, long token, RuntimeException failure) {
      try { edit.cancel(); } catch (RuntimeException cleanup) { suppress(failure, cleanup); }
      if (token == 0L) return;
      try { pointer.finish(token); } catch (RuntimeException cleanup) { suppress(failure, cleanup); }
   }
   private static void suppress(RuntimeException failure, RuntimeException cleanup) {
      if (cleanup != failure) failure.addSuppressed(cleanup);
   }
}
