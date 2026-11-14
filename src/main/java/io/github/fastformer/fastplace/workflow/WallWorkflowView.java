package io.github.fastformer.fastplace.workflow;

import io.github.fastformer.common.geometry.GeometryMode;
import net.minecraft.core.BlockPos;

public record WallWorkflowView(GeometryWorkflowView.Context context, BlockPos extrusion) implements GeometryWorkflowView {
   @Override public GeometryMode mode() { return GeometryMode.WALL; }
}
