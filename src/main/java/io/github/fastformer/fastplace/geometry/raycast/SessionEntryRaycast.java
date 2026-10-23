package io.github.fastformer.fastplace.geometry.raycast;

import io.github.fastformer.fastplace.interaction.InteractionShapeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Entry rays include every fluid unless Alt is held; snapshots retain the same shapes. */
public final class SessionEntryRaycast {
   private SessionEntryRaycast() { }

   public static BlockHitResult clip(Player player, Vec3 eye, Vec3 view, boolean throughFluids) {
      return LongRangeBlockRaycast.clipForSelection(player.level(), player, eye, view, throughFluids).hit();
   }

   public static InteractionShapeSnapshot snapshot(BlockGetter level, BlockPos position) {
      return InteractionShapeSnapshot.capture(level, position);
   }
}
