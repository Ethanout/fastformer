package io.github.fastformer.client.input;

import io.github.fastformer.client.session.OperationDraftIdentity;
import io.github.fastformer.fastplace.selection.OperationPointDragConstraint;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Immutable operation-point input sampled at the physical press boundary. */
record OperationPointDragPress(
   long identity, int button, long occurredAtNanos, OperationDraftIdentity selection, long revision,
   OperationCallbackScope callbackScope, int pointIndex, BlockPos initialPoint,
   SelectionPrism.GridPlane plane, Vec3 planeGrabOffset, SelectionPrism.GridLine line,
   double lineGrabBaseline, Vec3 axisBaselines, OperationPointDragConstraint constraint, Vec3 eye, Vec3 view
) {
   OperationPointDragPress {
      if (identity <= 0 || button < 0 || button > 1 || selection == null || callbackScope == null || pointIndex < 0
         || initialPoint == null || planeGrabOffset == null || axisBaselines == null || constraint == null) {
         throw new IllegalArgumentException("Operation point drag press requires a complete captured target");
      }
      if (eye == null || view == null) throw new IllegalArgumentException("Operation point drag press requires a frozen view");
      initialPoint = initialPoint.immutable();
   }
}
