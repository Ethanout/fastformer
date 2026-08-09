package io.github.fastformer.client;

import io.github.fastformer.fastplace.OperationStackRegion;
import io.github.fastformer.fastplace.OperationStageMode;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import net.minecraft.core.BlockPos;

final class OperationGizmoPresentation {
   private OperationGizmoPresentation() {
   }

   static float alpha(boolean nearCenter) {
      return nearCenter ? 1.0F : 0.5F;
   }

   static List<AxisGizmo.Operation> operations(OperationStageMode stageMode) {
      return switch (stageMode) {
         case TRANSFORM -> List.of(AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE, AxisGizmo.Operation.ROTATE);
         case SWEEP, LOFT -> List.of();
      };
   }

   static List<BlockPos> stackOffsets(BlockPos min, BlockPos max, int limit) {
      return new OperationStackRegion(min, max).repetitions(limit);
   }
}
