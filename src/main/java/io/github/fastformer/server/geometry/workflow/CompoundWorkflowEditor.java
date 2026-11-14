package io.github.fastformer.server.geometry.workflow;

import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.session.CompoundGeometryState;

public final class CompoundWorkflowEditor extends GeometryWorkflowEditor<CompoundGeometryState> {
   public CompoundWorkflowEditor() { super(CompoundGeometryState.class, GeometryMode.COMPOUND); }
   @Override protected boolean onScroll(CompoundGeometryState state, GeometryActionContext context, int steps) {
      if (!allows(state, GeometryAction.SCALAR_ADJUST)) return false;
      state.cycleCompoundShapeVariant(steps);
      return true;
   }
}
