package io.github.fastformer.fastplace;

import net.minecraft.core.BlockPos;

final class OperationExecutionSemantics {
   private OperationExecutionSemantics() {
   }

   static boolean clearsSource(OperationMode mode, BlockPos translation, boolean copy) {
      return !copy && (mode == OperationMode.MOVE || !translation.equals(BlockPos.ZERO));
   }
}
