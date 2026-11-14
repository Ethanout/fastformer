package io.github.fastformer.server.geometry.workflow;

import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.common.geometry.interaction.PointerGesture;
import io.github.fastformer.common.geometry.session.ConvexPolyhedronGeometryState;
import io.github.fastformer.common.geometry.workflow.plan.ConvexPolyhedronWorkflow;
import io.github.fastformer.core.math.Axis;
import net.minecraft.world.phys.Vec3;

public final class ConvexPolyhedronWorkflowEditor extends GeometryWorkflowEditor<ConvexPolyhedronGeometryState> {
   public ConvexPolyhedronWorkflowEditor() { super(ConvexPolyhedronGeometryState.class, GeometryMode.CONVEX_POLYHEDRON); }
   @Override protected boolean clearSelection(ConvexPolyhedronGeometryState state, PointerGesture gesture) {
      if ((gesture != PointerGesture.LEFT_CLICK && gesture != PointerGesture.RIGHT_CLICK)
         || state.selectedControlPoint() < 0) return false;
      state.clearSelectedControlPoint();
      return true;
   }
   @Override protected void onAddPoint(ConvexPolyhedronGeometryState state, GeometryActionContext context, GeometryHit hit) {
      if (state.pointLocations().size() >= ConvexPolyhedronWorkflow.MAX_CONTROL_POINTS) return;
      state.clearSelectedControlPoint();
      Vec3 candidate = hit.halfGridPoint(context.modifierHeld());
      if (state.pointLocations().stream().noneMatch(point -> point.distanceToSqr(candidate) < 1.0E-12)) state.addPoint(candidate);
   }
   @Override protected boolean onGizmoDrag(ConvexPolyhedronGeometryState state, GeometryActionContext context,
      AxisGizmo.Operation operation, Axis axis, int steps) {
      if (state.selectedControlPoint() < 0 || steps == 0 || operation != AxisGizmo.Operation.MOVE) return false;
      Vec3 direction = switch (axis) {
         case X -> new Vec3(1, 0, 0);
         case Y -> new Vec3(0, 1, 0);
         case Z -> new Vec3(0, 0, 1);
      };
      state.moveSelectedPoint(direction.scale((double)steps * 0.5));
      return true;
   }
   @Override protected boolean onInteraction(ConvexPolyhedronGeometryState state, GeometryActionContext context,
      GeometryInteractionTarget.TargetType targetType, int index, GeometryInteractionAction action, PointerGesture gesture) {
      if (targetType != GeometryInteractionTarget.TargetType.CONTROL_POINT
         || action != GeometryInteractionAction.SELECT_CONTROL_POINT
         || (gesture != PointerGesture.LEFT_CLICK && gesture != PointerGesture.RIGHT_CLICK)
         || index < 0 || index >= state.pointLocations().size()) return false;
      state.selectControlPoint(index);
      return true;
   }
}
