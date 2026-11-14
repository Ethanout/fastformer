package io.github.fastformer.fastplace.workflow;

import io.github.fastformer.common.geometry.GeometryMode;

public record ConvexWorkflowView(GeometryWorkflowView.Context context, int selectedPointIndex) implements GeometryWorkflowView {
   public ConvexWorkflowView {
      selectedPointIndex = selectedPointIndex >= 0 && selectedPointIndex < context.pointCount() ? selectedPointIndex : -1;
   }
   @Override public GeometryMode mode() { return GeometryMode.CONVEX_POLYHEDRON; }
}
