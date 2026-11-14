package io.github.fastformer.common.placement.context;

import io.github.fastformer.common.placement.PlacementContextSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/** Captures runtime placement inputs and adapts frozen data to vanilla placement queries. */
public final class PlacementContexts {
   private PlacementContexts() {}

   public static PlacementContextSnapshot capture(Level level, Player player, ItemStack stack,
      BlockHitResult hit, boolean embedded) {
      BlockPlaceContext vanilla = new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, stack, hit);
      return new PlacementContextSnapshot(hit.getBlockPos(), hit.getLocation(), hit.getDirection(), hit.isInside(),
         embedded || vanilla.replacingClickedOnBlock(), player.getYRot(), player.getDirection(),
         player.getXRot() < 0.0F ? Direction.UP : Direction.DOWN, List.of(Direction.orderedByNearest(player)),
         player.isSecondaryUseActive());
   }

   public static BlockPlaceContext context(PlacementContextSnapshot snapshot, Level level, Player player, ItemStack stack) {
      return new FrozenBlockPlaceContext(level, player, stack, snapshot);
   }

   private static final class FrozenBlockPlaceContext extends BlockPlaceContext {
      private final PlacementContextSnapshot snapshot;

      FrozenBlockPlaceContext(Level level, Player player, ItemStack stack, PlacementContextSnapshot snapshot) {
         super(level, player, InteractionHand.MAIN_HAND, stack,
            new BlockHitResult(snapshot.hitLocation(), snapshot.clickedFace(), snapshot.hitBlock(), snapshot.inside()));
         this.snapshot = snapshot;
         this.replaceClicked = snapshot.replacingClickedBlock();
      }

      @Override public Direction getHorizontalDirection() { return snapshot.horizontalDirection(); }
      @Override public float getRotation() { return snapshot.rotation(); }
      @Override public boolean isSecondaryUseActive() { return snapshot.secondaryUseActive(); }
      @Override public Direction getNearestLookingDirection() { return snapshot.nearestDirections().getFirst(); }
      @Override public Direction getNearestLookingVerticalDirection() { return snapshot.verticalDirection(); }

      @Override public Direction[] getNearestLookingDirections() {
         ArrayList<Direction> directions = new ArrayList<>(snapshot.nearestDirections());
         if (!replaceClicked) {
            Direction preferred = getClickedFace().getOpposite();
            directions.remove(preferred);
            directions.addFirst(preferred);
         }
         return directions.toArray(Direction[]::new);
      }
   }
}
