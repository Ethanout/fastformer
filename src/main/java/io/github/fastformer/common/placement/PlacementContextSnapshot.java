package io.github.fastformer.common.placement;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Immutable placement inputs frozen when the first building point is accepted. */
public record PlacementContextSnapshot(
   BlockPos hitBlock, Vec3 hitLocation, Direction clickedFace, boolean inside,
   boolean replacingClickedBlock, float rotation, Direction horizontalDirection,
   Direction verticalDirection, List<Direction> nearestDirections, boolean secondaryUseActive,
   CapturedState capturedState
) {
   public record CapturedState(Item item, BlockState state) {
      public CapturedState {
         Objects.requireNonNull(item);
         Objects.requireNonNull(state);
      }
   }

   public PlacementContextSnapshot(BlockPos hitBlock, Vec3 hitLocation, Direction clickedFace,
      boolean inside, boolean replacingClickedBlock, float rotation, Direction horizontalDirection,
      Direction verticalDirection, List<Direction> nearestDirections, boolean secondaryUseActive) {
      this(hitBlock, hitLocation, clickedFace, inside, replacingClickedBlock, rotation, horizontalDirection,
         verticalDirection, nearestDirections, secondaryUseActive, null);
   }

   public PlacementContextSnapshot {
      hitBlock = hitBlock.immutable();
      hitLocation = finite(hitLocation) ? hitLocation : Vec3.atCenterOf(hitBlock);
      clickedFace = clickedFace == null ? Direction.UP : clickedFace;
      rotation = Float.isFinite(rotation) ? rotation : 0.0F;
      horizontalDirection = horizontalDirection == null ? Direction.NORTH : horizontalDirection;
      verticalDirection = verticalDirection == null ? Direction.UP : verticalDirection;
      nearestDirections = validDirections(nearestDirections) ? List.copyOf(nearestDirections)
         : List.of(Direction.UP, Direction.NORTH, Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.DOWN);
   }

   public BlockPos placementPosition() {
      return replacingClickedBlock ? hitBlock : hitBlock.relative(clickedFace);
   }

   public PlacementContextSnapshot withCapturedState(Item item, BlockState state) {
      return new PlacementContextSnapshot(hitBlock, hitLocation, clickedFace, inside, replacingClickedBlock,
         rotation, horizontalDirection, verticalDirection, nearestDirections, secondaryUseActive, new CapturedState(item, state));
   }

   /** Reuses the first interaction's orientation at its captured block. */
   public PlacementContextSnapshot anchoredAt(BlockPos position) {
      return new PlacementContextSnapshot(position, hitLocation.add(Vec3.atLowerCornerOf(position.subtract(hitBlock))),
         clickedFace, inside, true, rotation, horizontalDirection, verticalDirection, nearestDirections, secondaryUseActive, capturedState);
   }

   private static boolean finite(Vec3 value) {
      return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
   }

   private static boolean validDirections(List<Direction> directions) {
      return directions != null && directions.size() == Direction.values().length
         && directions.stream().distinct().count() == Direction.values().length;
   }
}
