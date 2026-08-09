package io.github.fastformer.fastplace;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

public final class PlaceableItems {
   private PlaceableItems() {
   }

   public static boolean isPlaceable(ItemStack stack) {
      return defaultBlockState(stack).isPresent();
   }

   public static Optional<BlockState> defaultBlockState(ItemStack stack) {
      if (stack.getItem() instanceof BlockItem blockItem) {
         return Optional.of(blockItem.getBlock().defaultBlockState());
      }
      return Optional.empty();
   }

   /** Resolves the same placement state as the vanilla BlockItem path. */
   public static Optional<BlockState> placementState(
      ItemStack stack, Player player, PlacementContextSnapshot snapshot
   ) {
      if (!(stack.getItem() instanceof BlockItem blockItem) || snapshot == null) {
         return defaultBlockState(stack);
      }
      BlockPlaceContext original = snapshot.context(player.level(), player, stack);
      BlockPlaceContext updated = blockItem.updatePlacementContext(original);
      BlockPlaceContext orientationContext = updated == null ? original : updated;
      BlockState state = updated == null ? null : blockItem.getPlacementState(updated);
      if (state == null) {
         state = blockItem.getBlock().getStateForPlacement(orientationContext);
      }
      if (state == null) {
         state = blockItem.getBlock().defaultBlockState();
      }
      BlockItemStateProperties properties = stack.getOrDefault(
         DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY
      );
      return Optional.of(properties.apply(state));
   }
}
