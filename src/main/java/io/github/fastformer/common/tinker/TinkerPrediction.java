package io.github.fastformer.common.tinker;

import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.tinker.rule.StandardTinkerRules;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Resolves empty-hand tinker intent without player settings, history, or world writes. */
public final class TinkerPrediction {
   private static final TinkerRules RULES = TinkerRules.create(StandardTinkerRules::register);

   private TinkerPrediction() {
   }

   public static boolean canEdit(Player player) {
      return player != null && player.isCreative() && player.getMainHandItem().isEmpty();
   }

   public static boolean withinInteractionRange(Player player, BlockHitResult hit) {
      return player != null && hit != null && hit.getType() == HitResult.Type.BLOCK
         && !LongRangeBlockRaycast.beyondInteractionRange(player, hit);
   }

   public static boolean consumes(Player player, BlockHitResult hit) {
      return previewAction(player, hit, true).consumed();
   }

   public static TinkerAction previewAction(Player player, BlockHitResult hit, boolean skipRepeatedCropStages) {
      if (!canEdit(player) || !withinInteractionRange(player, hit)) return TinkerAction.PASS;
      return resolveAction(player.level(), hit, player.isShiftKeyDown(), skipRepeatedCropStages);
   }

   public static TinkerAction resolveAction(Level level, BlockHitResult hit, boolean shift, boolean skipRepeatedCropStages) {
      return RULES.resolve(TinkerContext.at(level, hit, shift, skipRepeatedCropStages));
   }
}
