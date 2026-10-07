package io.github.fastformer.fastplace.placement.context;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class PlaceableItems {
   private PlaceableItems() {
   }

   public static boolean isPlaceable(ItemStack stack) {
      return defaultBlockState(stack).isPresent();
   }

   public static Optional<BlockState> defaultBlockState(ItemStack stack) {
      if (stack.getItem() instanceof BlockItem blockItem) {
         return Optional.of(initialPlacementState(blockItem.getBlock().defaultBlockState()));
      }
      return Optional.empty();
   }

   /** Resolves real support, virtual support, data pack rules, then the default state. */
   public static Optional<BlockState> placementState(
      ItemStack stack, Player player, PlacementContextSnapshot snapshot
   ) {
      if (!(stack.getItem() instanceof BlockItem blockItem) || snapshot == null) {
         return defaultBlockState(stack);
      }
      if (snapshot.capturedState() != null && snapshot.capturedState().item() == stack.getItem()) {
         return Optional.of(snapshot.capturedState().state());
      }
      BlockPlaceContext original = snapshot.context(player.level(), player, stack);
      BlockPlaceContext updated = blockItem.updatePlacementContext(original);
      BlockPlaceContext orientationContext = updated == null ? original : updated;
      BlockState state = updated == null ? null : VirtualSupportPlacement.supportedState(blockItem, updated);
      if (state == null) {
         state = VirtualSupportPlacement.resolve(blockItem, original, updated);
      }
      if (state == null) {
         state = PlacementFallbacks.resolve(blockItem.getBlock().defaultBlockState(), orientationContext);
      }
      BlockItemStateProperties properties = stack.getOrDefault(
         DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY
      );
      return Optional.of(properties.apply(initialPlacementState(state)));
   }

   private static BlockState initialPlacementState(BlockState state) {
      return state.is(Blocks.PUMPKIN_STEM) || state.is(Blocks.MELON_STEM)
         ? state.setValue(BlockStateProperties.AGE_7, 7) : state;
   }
}
