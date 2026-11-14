package io.github.fastformer.common.geometry.workflow;

import io.github.fastformer.common.geometry.GeometryMode;

public record CompoundWorkflowView(GeometryWorkflowView.Context context, int compoundShapeVariant) implements GeometryWorkflowView {
   @Override public GeometryMode mode() { return GeometryMode.COMPOUND; }
}
