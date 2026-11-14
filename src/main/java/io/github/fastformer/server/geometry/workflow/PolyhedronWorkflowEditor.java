package io.github.fastformer.server.geometry.workflow;

import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.session.PolyhedronGeometryState;
import io.github.fastformer.core.math.Axis;
import net.minecraft.core.BlockPos;

public final class PolyhedronWorkflowEditor extends GeometryWorkflowEditor<PolyhedronGeometryState> {
   public PolyhedronWorkflowEditor() { super(PolyhedronGeometryState.class, GeometryMode.POLYHEDRON); }
   @Override protected void onAddPoint(PolyhedronGeometryState state, GeometryActionContext context, BlockPos point) {
      onAddPoint(state, context, GeometryHit.point(point));
   }
   @Override protected void onAddPoint(PolyhedronGeometryState state, GeometryActionContext context, GeometryHit hit) {
      if (!state.hasPolyhedronFirstInput()) state.addPoint(hit.spherePoint(context.modifierHeld()));
      else if (!state.closed()) state.enterPolyhedronAdjust(hit.spherePoint(context.modifierHeld()));
   }
   @Override protected boolean onScroll(PolyhedronGeometryState state, GeometryActionContext context, int steps) {
      if (!allows(state, GeometryAction.SCALAR_ADJUST)) return false;
      if (!state.closed()) state.adjustPolyhedronRadius(steps);
      return true;
   }
   @Override protected boolean onCycleMode(PolyhedronGeometryState state, GeometryActionContext context) {
      if (state.closed()) state.togglePolyhedronGizmoFrame();
      else state.cyclePolyhedronSizeMode();
      return true;
   }
   @Override protected boolean onGizmoDrag(PolyhedronGeometryState state, GeometryActionContext context,
      AxisGizmo.Operation operation, Axis axis, int steps) {
      if (!state.closed() || steps == 0 || operation == null || axis == null) return false;
      return switch (operation) {
         case MOVE -> {
            state.movePolyhedron(state.polyhedronGizmoAxis(axis).scale((double)steps * 0.5));
            yield true;
         }
         case SCALE -> {
            state.adjustPolyhedronScale(axis, steps);
            yield true;
         }
         case ROTATE -> false;
      };
   }
}
