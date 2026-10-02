package io.github.fastformer.fastplace.placement.replace;

import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** The preview and server use the same outline ray and replacement state. */
public record QuickReplaceTarget(BlockPos position, BlockState state) {
   public static QuickReplaceTarget resolve(Player player) {
      if (player == null || !PlaceableItems.isPlaceable(player.getMainHandItem())) return null;
      HitResult result = player.pick(player.blockInteractionRange(), 1.0F, false);
      if (!(result instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
      var level = player.level();
      if (!level.getWorldBorder().isWithinBounds(hit.getBlockPos())) return null;
      PlacementContextSnapshot context = PlacementContextSnapshot.capture(
         level, player, player.getMainHandItem(), hit, false);
      context = new PlacementContextSnapshot(context.hitBlock(), context.hitLocation(), context.clickedFace(),
         context.inside(), true, context.rotation(), context.horizontalDirection(), context.verticalDirection(),
         context.nearestDirections(), context.secondaryUseActive());
      return PlaceableItems.placementState(player.getMainHandItem(), player, context)
         .map(state -> new QuickReplaceTarget(hit.getBlockPos().immutable(),
            copySharedProperties(level.getBlockState(hit.getBlockPos()), state))).orElse(null);
   }

   public static BlockState copySharedProperties(BlockState source, BlockState target) {
      BlockState result = target;
      for (Property<?> property : source.getProperties()) {
         if (result.hasProperty(property)) result = copyProperty(source, result, property);
      }
      return result;
   }

   private static <T extends Comparable<T>> BlockState copyProperty(BlockState source, BlockState target, Property<T> property) {
      T value = source.getValue(property);
      return property.getPossibleValues().contains(value) ? target.setValue(property, value) : target;
   }
}
