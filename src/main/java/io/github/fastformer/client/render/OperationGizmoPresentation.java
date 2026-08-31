package io.github.fastformer.client.render;

import io.github.fastformer.fastplace.OperationStackRegion;
import io.github.fastformer.fastplace.OperationStageMode;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import net.minecraft.core.BlockPos;

public final class OperationGizmoPresentation {
   private OperationGizmoPresentation() {
   }

   public static float alpha(boolean nearCenter) {
      return nearCenter ? 1.0F : 0.5F;
   }

   public static List<AxisGizmo.Operation> operations(OperationStageMode stageMode) {
      return switch (stageMode) {
         case TRANSFORM -> List.of(AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE, AxisGizmo.Operation.ROTATE);
         case SWEEP, LOFT -> List.of();
      };
   }

   public static List<BlockPos> stackOffsets(BlockPos min, BlockPos max, int limit) {
      return new OperationStackRegion(min, max).repetitions(limit);
   }
}
