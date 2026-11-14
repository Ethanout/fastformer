package io.github.fastformer.server.geometry.workflow;

import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.common.geometry.interaction.PointerGesture;
import io.github.fastformer.common.geometry.session.WallGeometryState;
import io.github.fastformer.common.geometry.workflow.plan.WallWorkflow;
import io.github.fastformer.common.selection.geometry.OperationGeometry;
import net.minecraft.core.BlockPos;

public final class WallWorkflowEditor extends GeometryWorkflowEditor<WallGeometryState> {
   public WallWorkflowEditor() { super(WallGeometryState.class, GeometryMode.WALL); }
   @Override protected void onAddPoint(WallGeometryState state, GeometryActionContext context, BlockPos point) {
      state.addOrClose(WallWorkflow.planePoint(state.blockPoints(), state.closed(), context.eye(), context.view(), point));
   }
   @Override protected void onAddPoint(WallGeometryState state, GeometryActionContext context, GeometryHit hit) {
      if (hit != null) onAddPoint(state, context, WallWorkflow.raycastPoint(hit, context.modifierHeld()));
   }
   @Override protected boolean onScroll(WallGeometryState state, GeometryActionContext context, int steps) {
      if (!allows(state, GeometryAction.SCALAR_ADJUST)) return false;
      state.adjustExtrusion(OperationGeometry.viewAxisStep(context.view(), steps));
      return true;
   }
   @Override protected boolean onClosePath(WallGeometryState state, GeometryActionContext context) {
      return state.blockPoints().size() >= 3 && state.close();
   }
   @Override protected boolean onInteraction(WallGeometryState state, GeometryActionContext context,
      GeometryInteractionTarget.TargetType targetType, int index, GeometryInteractionAction action, PointerGesture gesture) {
      if (targetType != GeometryInteractionTarget.TargetType.CLOSE_PATH || index != 0
         || action != GeometryInteractionAction.CLOSE_PATH
         || (gesture != PointerGesture.RIGHT_CLICK && gesture != PointerGesture.RIGHT_DOUBLE_CLICK)) return false;
      return onClosePath(state, context);
   }
}
