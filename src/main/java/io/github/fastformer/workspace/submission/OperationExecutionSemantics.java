package io.github.fastformer.workspace.submission;

import io.github.fastformer.fastplace.selection.OperationMode;

import io.github.fastformer.fastplace.world.*;

import net.minecraft.core.BlockPos;

public final class OperationExecutionSemantics {
   private OperationExecutionSemantics() {
   }

   public static boolean clearsSource(OperationMode mode, BlockPos translation, boolean copy) {
      return !copy && (mode == OperationMode.MOVE || !translation.equals(BlockPos.ZERO));
   }
}
