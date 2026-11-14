package io.github.fastformer.server.selection;

import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Validates editor points against the editor ray range, dimension height and world border. */
public final class ClientPointValidator {
   private ClientPointValidator() {}

   public static boolean accepts(ServerPlayer player, BlockPos target) {
      if (player == null || target == null) return false;
      var level = player.serverLevel();
      return level.isInWorldBounds(target)
         && level.getWorldBorder().isWithinBounds(target)
         && player.getEyePosition().distanceToSqr(Vec3.atCenterOf(target))
            <= LongRangeBlockRaycast.MAX_REACH * LongRangeBlockRaycast.MAX_REACH;
   }
}
