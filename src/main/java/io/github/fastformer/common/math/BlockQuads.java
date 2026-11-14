package io.github.fastformer.common.math;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Exact grid parallelogram identity; degeneracy and planarity are the caller's separate checks. */
public final class BlockQuads {
   private BlockQuads() {}

   public static boolean isParallelogram(List<BlockPos> corners) {
      return corners.size() == 4 && isParallelogram(corners.get(0), corners.get(1), corners.get(2), corners.get(3));
   }

   /** Corners follow their perimeter; opposite sums use long to avoid integer overflow. */
   public static boolean isParallelogram(BlockPos first, BlockPos second, BlockPos third, BlockPos fourth) {
      return (long)first.getX() + third.getX() == (long)second.getX() + fourth.getX()
         && (long)first.getY() + third.getY() == (long)second.getY() + fourth.getY()
         && (long)first.getZ() + third.getZ() == (long)second.getZ() + fourth.getZ();
   }

   public static boolean isParallelogramAfterFlooring(List<Vec3> corners) {
      return corners.size() == 4 && isParallelogram(BlockPos.containing(corners.get(0)), BlockPos.containing(corners.get(1)),
         BlockPos.containing(corners.get(2)), BlockPos.containing(corners.get(3)));
   }
}
