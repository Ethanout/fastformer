package io.github.fastformer.client.placement;

import io.github.fastformer.fastplace.PlaceableItems;
import io.github.fastformer.fastplace.PlacementContextSnapshot;
import io.github.fastformer.fastplace.LongRangeBlockRaycast;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Client-side state for Axiom-style direct block replacement. */
public final class QuickReplaceMode {
   private static boolean active;

   private QuickReplaceMode() {
   }

   public static boolean active() {
      return active;
   }

   public static boolean toggle(Minecraft minecraft) {
      if (minecraft == null || minecraft.player == null) {
         return false;
      }
      active = !active;
      return active;
   }

   public static void clear() {
      active = false;
   }

   public static boolean canReplace(Minecraft minecraft) {
      return active && minecraft != null && minecraft.player != null
         && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem());
   }

   public static Preview preview(Minecraft minecraft) {
      if (!canReplace(minecraft) || minecraft.level == null) {
         return null;
      }
      LocalPlayer player = minecraft.player;
      BlockHitResult hit = LongRangeBlockRaycast.clip(
         minecraft.level, player, player.getEyePosition(), player.getViewVector(1.0F)
         , net.minecraft.world.level.ClipContext.Block.COLLIDER
      ).hit();
      if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
         return null;
      }
      Optional<BlockState> state = PlaceableItems.placementState(
         player.getMainHandItem(), player,
         PlacementContextSnapshot.capture(minecraft.level, player, player.getMainHandItem(), hit, false)
      );
      return state.map(value -> new Preview(hit.getBlockPos().immutable(), value)).orElse(null);
   }

   public record Preview(BlockPos position, BlockState state) {
   }
}
