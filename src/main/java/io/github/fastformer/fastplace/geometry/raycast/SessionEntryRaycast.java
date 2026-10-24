package io.github.fastformer.fastplace.geometry.raycast;

import io.github.fastformer.fastplace.interaction.InteractionShapeSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Direct entries use FF fluid rules; vanilla double-clicks retain the first outline's rules. */
public final class SessionEntryRaycast {
   private SessionEntryRaycast() { }

   public static BlockHitResult clip(Player player, Vec3 eye, Vec3 view, boolean throughFluids) {
      return LongRangeBlockRaycast.clipForSelection(player.level(), player, eye, view, throughFluids).hit();
   }

   public static BlockHitResult clip(Player player, Vec3 eye, Vec3 view, InteractionRaycastRules rules) {
      return LongRangeBlockRaycast.clipForSelection(player.level(), player, eye, view, rules).hit();
   }

   public static InteractionShapeSnapshot snapshot(BlockGetter level, BlockPos position) {
      return InteractionShapeSnapshot.capture(level, position);
   }

   public static InteractionShapeSnapshot snapshot(BlockGetter level, BlockPos position, InteractionRaycastRules rules) {
      return InteractionShapeSnapshot.capture(level, position, rules.throughFluids());
   }

   public static InteractionShapeSnapshot snapshot(Player player, BlockPos position, InteractionRaycastRules rules) {
      return InteractionShapeSnapshot.capture(player.level(), position, rules.throughFluids(),
         net.minecraft.world.phys.shapes.CollisionContext.of(player));
   }
}
