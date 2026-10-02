package io.github.fastformer.fastplace.placement.context;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

public final class VirtualSupportPlacement {
   private VirtualSupportPlacement() {}

   public static BlockState resolve(BlockItem item, BlockPlaceContext original, BlockPlaceContext updated) {
      BlockPlaceContext orientation = updated == null ? original : updated;
      BlockPos target = orientation.getClickedPos();
      var level = orientation.getLevel();
      if (!level.isInWorldBounds(target)) return null;
      for (Direction direction : orientation.getNearestLookingDirections()) {
         BlockPos support = target.relative(direction);
         if (!level.isInWorldBounds(support)) continue;
         BlockState state = PlacementSupportQuery.withSupport(level, support, () -> {
            BlockPlaceContext retry = item.updatePlacementContext(original);
            if (retry == null || !retry.getClickedPos().equals(target)) return null;
            return supportedState(item, retry);
         });
         if (state != null) return state;
      }
      return null;
   }

   /** Preserve creative placement through entities, but do not discard support requirements. */
   public static BlockState supportedState(BlockItem item, BlockPlaceContext context) {
      BlockState state = item.getPlacementState(context);
      if (state != null) return state;
      state = item.getBlock().getStateForPlacement(context);
      return state != null && state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
   }
}
