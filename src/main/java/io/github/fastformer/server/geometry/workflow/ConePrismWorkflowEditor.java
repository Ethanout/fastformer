package io.github.fastformer.server.geometry.workflow;

import io.github.fastformer.common.geometry.ConePlaneMode;
import io.github.fastformer.common.geometry.ConePrismStage;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.model.TransformFrame;
import io.github.fastformer.common.geometry.session.ConePrismGeometryState;
import io.github.fastformer.common.geometry.workflow.ConeWorkflowView;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflowView;
import io.github.fastformer.common.geometry.workflow.plan.ConePrismWorkflow;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.generation.shape.ConePrismGenerator;
import io.github.fastformer.common.geometry.generation.shape.ConePrismGeometry;
import io.github.fastformer.common.geometry.generation.shape.ConePrismParameters;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class ConePrismWorkflowEditor extends GeometryWorkflowEditor<ConePrismGeometryState> {
   public ConePrismWorkflowEditor() { super(ConePrismGeometryState.class, GeometryMode.CONE_PRISM); }
   @Override protected void onOpen(ConePrismGeometryState state, GeometryActionContext context) {
      state.setConePlaneMode(context.preferences().conePlaneMode());
   }
   private static ConePrismParameters parameters(ConePrismGeometryState state) {
      return ConePrismWorkflow.parameters((ConeWorkflowView)GeometryWorkflowView.from(state, false));
   }
   @Override protected void onAddPoint(ConePrismGeometryState state, GeometryActionContext context, BlockPos point) {
      onAddPoint(state, context, GeometryHit.point(point));
   }
   @Override protected void onAddPoint(ConePrismGeometryState state, GeometryActionContext context, GeometryHit hit) {
      if (!allows(state, GeometryAction.POINT_INPUT)) return;
      if (state.coneStage() == ConePrismStage.BODY) {
         var parameters = parameters(state);
         state.setConeShapeVariant(ConePrismWorkflow.nearCenterline(parameters, context.eye(), context.view()) ? 1 : 0);
         state.addPoint(ConePrismWorkflow.heightPoint(parameters, context.eye(), context.view(), hit.conePoint(context.modifierHeld())));
      } else if (state.conePlaneMode() == ConePlaneMode.RADIUS && state.blockPoints().size() == 1) {
         Vec3 center = state.pointLocations().getFirst();
         Vec3 candidate = hit.conePoint(context.modifierHeld());
         state.setConeRadius(ConePrismWorkflow.horizontalRadius(candidate.subtract(center)));
         state.addPoint(ConePrismWorkflow.radiusPoint(center, candidate, state.coneRadius()));
      } else state.addPoint(hit.conePoint(context.modifierHeld()));
   }
   @Override protected boolean onScroll(ConePrismGeometryState state, GeometryActionContext context, int steps) {
      if (!allows(state, GeometryAction.SCALAR_ADJUST)) return false;
      if (ConePrismWorkflow.scrollsTopScale(parameters(state), context.view())) state.adjustConeTopScale(steps);
      else {
         ConePrismGeometry.Base base = ConePrismGenerator.baseInfo(parameters(state));
         if (base != null) state.adjustConeTopOffset(context.view(), base.axisU(), base.axisV(), steps);
      }
      return true;
   }
   @Override protected boolean onCycleMode(ConePrismGeometryState state, GeometryActionContext context) {
      if (!allows(state, GeometryAction.MODE_CYCLE)) return false;
      if (state.coneStage() == ConePrismStage.ADJUST) state.toggleConeGizmoFrame();
      else {
         state.cycleConePlaneMode();
         context.preferences().saveConePlaneMode().accept(state.conePlaneMode());
      }
      return true;
   }
   @Override protected boolean onGizmoDrag(ConePrismGeometryState state, GeometryActionContext context,
      AxisGizmo.Operation operation, Axis axis, int steps) {
      if (steps == 0 || state.coneStage() != ConePrismStage.ADJUST) return false;
      ConePrismGeometry geometry = ConePrismGeometry.from(parameters(state));
      if (geometry == null || !geometry.heightReady()) return false;
      ConePrismGeometry.Base base = geometry.base();
      TransformFrame frame = ConePrismWorkflow.coneFrame(geometry, state.coneGizmoLocal());
      Vec3 direction = frame.axis(axis);
      double normalComponent = direction.dot(base.normal());
      double uComponent = direction.dot(base.axisU());
      double vComponent = direction.dot(base.axisV());
      switch (operation) {
         case MOVE -> state.moveCone(direction.scale((double)steps * 0.5));
         case SCALE -> {
            if (Math.abs(normalComponent) >= Math.max(Math.abs(uComponent), Math.abs(vComponent)))
               state.adjustConeHeight(base.center(), base.normal(), (double)steps * 0.5);
            else if (Math.abs(uComponent) >= Math.abs(vComponent)) state.adjustConeScale(Axis.X, steps, base.radius());
            else state.adjustConeScale(Axis.Z, steps, base.radius());
         }
         case ROTATE -> {
            if (!state.coneGizmoLocal() || axis != Axis.Y) return false;
            state.rotateCone(steps, 1024);
         }
      }
      return true;
   }
}
