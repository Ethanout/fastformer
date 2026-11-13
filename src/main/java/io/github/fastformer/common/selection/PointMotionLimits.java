package io.github.fastformer.common.selection;

import net.minecraft.core.BlockPos;

/** Shared per-packet drag limit; long arithmetic also rejects overflowing coordinates. */
public final class PointMotionLimits {
   public static final int MAX_STEP = 128;

   private PointMotionLimits() {}

   public static boolean accepts(BlockPos current, BlockPos target) {
      return current != null && target != null
         && Math.abs((long) target.getX() - current.getX()) <= MAX_STEP
         && Math.abs((long) target.getY() - current.getY()) <= MAX_STEP
         && Math.abs((long) target.getZ() - current.getZ()) <= MAX_STEP;
   }
}
