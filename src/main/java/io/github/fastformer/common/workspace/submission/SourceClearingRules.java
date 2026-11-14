package io.github.fastformer.common.workspace.submission;

import io.github.fastformer.common.selection.OperationMode;


import net.minecraft.core.BlockPos;

/** Decides whether a submitted copy, move, or offset operation clears its source. */
public final class SourceClearingRules {
   private SourceClearingRules() {
   }

   public static boolean clearsSource(OperationMode mode, BlockPos translation, boolean copy) {
      return !copy && (mode == OperationMode.MOVE || !translation.equals(BlockPos.ZERO));
   }
}
