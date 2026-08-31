package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.OperationMode;
import io.github.fastformer.fastplace.OperationStackRegion;
import io.github.fastformer.fastplace.OperationStageMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Mutable transform cell owned by one operation session. */
final class OperationTransformState {
   OperationMode mode = OperationMode.MOVE;
   BlockPos translation = BlockPos.ZERO;
   BlockPos stackVector = BlockPos.ZERO;
   OperationStackRegion stackRegion = OperationStackRegion.origin();
   Vec3 rotation = Vec3.ZERO;
   OperationStageMode stageMode = OperationStageMode.TRANSFORM;
   boolean adjustmentStarted;

   void clear() {
      this.mode = OperationMode.MOVE;
      this.translation = BlockPos.ZERO;
      this.stackVector = BlockPos.ZERO;
      this.stackRegion = OperationStackRegion.origin();
      this.rotation = Vec3.ZERO;
      this.stageMode = OperationStageMode.TRANSFORM;
      this.adjustmentStarted = false;
   }
}
