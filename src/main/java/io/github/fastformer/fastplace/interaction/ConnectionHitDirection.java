package io.github.fastformer.fastplace.interaction;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;

/** Selects the connection arm under a wrench hit. */
public final class ConnectionHitDirection {
   private ConnectionHitDirection() { }

   public static Direction resolve(BlockHitResult hit) {
      double x = hit.getLocation().x - hit.getBlockPos().getX() - 0.5;
      double z = hit.getLocation().z - hit.getBlockPos().getZ() - 0.5;
      if (Math.max(Math.abs(x), Math.abs(z)) <= 0.125) return hit.getDirection();
      return Math.abs(x) > Math.abs(z) ? (x < 0 ? Direction.WEST : Direction.EAST)
         : (z < 0 ? Direction.NORTH : Direction.SOUTH);
   }

}
