package io.github.fastformer.fastplace.workflow;

import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.cone.ConePlaneMode;
import io.github.fastformer.fastplace.geometry.cone.ConePrismStage;
import net.minecraft.world.phys.Vec3;

public record ConeWorkflowView(GeometryWorkflowView.Context context, int coneShapeVariant, ConePlaneMode conePlaneMode,
   double coneRadius, double coneScaleX, double coneScaleZ, double coneTopScaleOffset, Vec3 coneTopOffset,
   double coneRotationRadians, boolean coneGizmoLocal) implements GeometryWorkflowView {
   public ConeWorkflowView {
      coneTopOffset = GeometryNumbers.finiteOrZero(coneTopOffset);
      coneRotationRadians = GeometryNumbers.finiteOr(coneRotationRadians, 0.0);
   }
   @Override public GeometryMode mode() { return GeometryMode.CONE_PRISM; }
   public ConePrismStage coneStage() { return conePlaneMode.stageFor(pointCount()); }
}
