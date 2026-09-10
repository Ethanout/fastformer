package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The vanilla placement inputs frozen when the first building point is accepted.
 * The held stack is deliberately not captured so Alt+scroll may change the block
 * while keeping the original placement orientation.
 */
public record PlacementContextSnapshot(
      BlockPos hitBlock,
      Vec3 hitLocation,
      Direction clickedFace,
      boolean inside,
      boolean replacingClickedBlock,
      float rotation,
      Direction horizontalDirection,
      Direction verticalDirection,
      List<Direction> nearestDirections,
      boolean secondaryUseActive
) {

   public PlacementContextSnapshot {
      hitBlock = hitBlock.immutable();
      hitLocation = finite(hitLocation) ? hitLocation : Vec3.atCenterOf(hitBlock);
      clickedFace = clickedFace == null ? Direction.UP : clickedFace;
      rotation = Float.isFinite(rotation) ? rotation : 0.0F;
      horizontalDirection = horizontalDirection == null ? Direction.NORTH : horizontalDirection;
      verticalDirection = verticalDirection == null ? Direction.UP : verticalDirection;
      nearestDirections = validDirections(nearestDirections)
         ? List.copyOf(nearestDirections)
         : List.of(Direction.UP, Direction.NORTH, Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.DOWN);
   }

   public static PlacementContextSnapshot capture(
      Level level,
      Player player,
      ItemStack stack,
      BlockHitResult entryHit,
      boolean embedded
   ) {
      BlockHitResult placementHit = entryHit;
      BlockPlaceContext vanilla = new BlockPlaceContext(
         level, player, InteractionHand.MAIN_HAND, stack, placementHit
      );
      return new PlacementContextSnapshot(
         placementHit.getBlockPos(),
         placementHit.getLocation(),
         placementHit.getDirection(),
         placementHit.isInside(),
         vanilla.replacingClickedOnBlock(),
         player.getYRot(),
         player.getDirection(),
         player.getXRot() < 0.0F ? Direction.UP : Direction.DOWN,
         List.of(Direction.orderedByNearest(player)),
         player.isSecondaryUseActive()
      );
   }

   public BlockPlaceContext context(Level level, Player player, ItemStack stack) {
      return new FrozenBlockPlaceContext(level, player, stack, this);
   }

   private static boolean finite(Vec3 value) {
      return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
   }

   private static boolean validDirections(List<Direction> directions) {
      return directions != null && directions.size() == Direction.values().length && directions.stream().distinct().count() == Direction.values().length;
   }

   private static final class FrozenBlockPlaceContext extends BlockPlaceContext {
      private final PlacementContextSnapshot snapshot;

      FrozenBlockPlaceContext(Level level, Player player, ItemStack stack, PlacementContextSnapshot snapshot) {
         super(
            level,
            player,
            InteractionHand.MAIN_HAND,
            stack,
            new BlockHitResult(snapshot.hitLocation, snapshot.clickedFace, snapshot.hitBlock, snapshot.inside)
         );
         this.snapshot = snapshot;
         this.replaceClicked = snapshot.replacingClickedBlock;
      }

      @Override
      public Direction getHorizontalDirection() {
         return this.snapshot.horizontalDirection;
      }

      @Override
      public float getRotation() {
         return this.snapshot.rotation;
      }

      @Override
      public boolean isSecondaryUseActive() {
         return this.snapshot.secondaryUseActive;
      }

      @Override
      public Direction getNearestLookingDirection() {
         return this.snapshot.nearestDirections.getFirst();
      }

      @Override
      public Direction getNearestLookingVerticalDirection() {
         return this.snapshot.verticalDirection;
      }

      @Override
      public Direction[] getNearestLookingDirections() {
         ArrayList<Direction> directions = new ArrayList<>(this.snapshot.nearestDirections);
         if (!this.replaceClicked) {
            Direction preferred = this.getClickedFace().getOpposite();
            directions.remove(preferred);
            directions.addFirst(preferred);
         }
         return directions.toArray(Direction[]::new);
      }
   }
}
