package io.github.fastformer.common.placement.context;

import io.github.fastformer.common.placement.PlacementContextSnapshot;
import io.github.fastformer.common.replace.QuickReplaceTarget;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared runtime prediction for replacement preview and server strokes. */
public final class QuickReplaceTargets {
   private QuickReplaceTargets() {}

   public static QuickReplaceTarget resolve(Player player) {
      if (player == null || !PlaceableItems.isPlaceable(player.getMainHandItem())) return null;
      return resolve(player, player.getEyePosition(), player.getViewVector(1));
   }

   public static QuickReplaceTarget resolve(Player player, Vec3 eye, Vec3 view) {
      if (player == null || !PlaceableItems.isPlaceable(player.getMainHandItem())) return null;
      return resolve(player, raycast(player, eye, view));
   }

   public static BlockHitResult raycast(Player player, Vec3 eye, Vec3 view) {
      HitResult result = player.level().clip(new ClipContext(eye, eye.add(view.scale(player.blockInteractionRange())),
         ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
      if (!(result instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
      var level = player.level();
      if (!level.getWorldBorder().isWithinBounds(hit.getBlockPos()) || !level.hasChunkAt(hit.getBlockPos())) return null;
      return hit;
   }

   public static QuickReplaceTarget resolve(Player player, BlockHitResult hit) {
      if (hit == null) return null;
      var level = player.level();
      PlacementContextSnapshot context = PlacementContexts.capture(level, player, player.getMainHandItem(), hit, false);
      context = new PlacementContextSnapshot(context.hitBlock(), context.hitLocation(), context.clickedFace(),
         context.inside(), true, context.rotation(), context.horizontalDirection(), context.verticalDirection(),
         context.nearestDirections(), context.secondaryUseActive());
      return PlaceableItems.placementState(player.getMainHandItem(), player, context)
         .map(state -> new QuickReplaceTarget(hit.getBlockPos().immutable(),
            QuickReplaceTarget.copySharedProperties(level.getBlockState(hit.getBlockPos()), state))).orElse(null);
   }
}
