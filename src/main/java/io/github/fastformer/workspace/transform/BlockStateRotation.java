package io.github.fastformer.workspace.transform;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.workspace.model.WorkspaceSymmetry;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.Vec3;

/** Rotates orientations that the block's state definition can represent. */
final class BlockStateRotation {
   private static final Map<Block, Map<Integer, List<BlockState>>> STAIR_ORIENTATIONS = new ConcurrentHashMap<>();

   private BlockStateRotation() {
   }

   static BlockState transform(BlockState state, WorkspaceSymmetry symmetry) {
      if (symmetry.identityOrientation()) return state;
      if (state.getBlock() instanceof StairBlock) {
         Direction facing = state.getValue(StairBlock.FACING);
         Vec3 forward = symmetry.vector(new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ()));
         return transformStairs(state, vector -> {
            Vec3 rotated = symmetry.vector(Vec3.atLowerCornerOf(vector));
            return new BlockPos((int)rotated.x, (int)rotated.y, (int)rotated.z);
         }, Direction.fromDelta((int)forward.x, (int)forward.y, (int)forward.z));
      }
      int determinant = (Math.abs(symmetry.x()) == 1 ? 1 : -1)
         * Integer.signum(symmetry.x()) * Integer.signum(symmetry.z());
      boolean reflected = determinant < 0;
      BlockState rotated = reflected ? state.mirror(Mirror.FRONT_BACK) : state;
      Vec3 east = symmetry.vector(new Vec3(1, 0, 0));
      Direction target = Direction.fromDelta((int)east.x, 0, (int)east.z);
      for (Rotation rotation : Rotation.values()) {
         if (rotation.rotate(reflected ? Direction.WEST : Direction.EAST) == target) {
            rotated = rotated.rotate(rotation);
            break;
         }
      }
      if (symmetry.y() < 0) {
         for (Property<?> property : rotated.getProperties()) {
            rotated = reflectVerticalProperty(rotated, property);
         }
      }
      return rotated;
   }

   private static <T extends Comparable<T>> BlockState reflectVerticalProperty(BlockState state, Property<T> property) {
      T value = state.getValue(property);
      String target;
      if (value == Direction.UP) target = "down";
      else if (value == Direction.DOWN) target = "up";
      else if (value == Half.TOP || value == SlabType.TOP) target = "bottom";
      else if (value == Half.BOTTOM || value == SlabType.BOTTOM) target = "top";
      else if (value == AttachFace.FLOOR) target = "ceiling";
      else if (value == AttachFace.CEILING) target = "floor";
      else return state;
      return property.getValue(target).map(candidate -> state.setValue(property, candidate)).orElse(state);
   }

   static BlockState rotate(BlockState state, AxisGizmo.Axis axis, int turns) {
      int normalized = Math.floorMod(turns, 4);
      if (normalized == 0) {
         return state;
      }
      if (axis == AxisGizmo.Axis.Y) {
         return state.rotate(switch (normalized) {
            case 1 -> Rotation.COUNTERCLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            default -> Rotation.CLOCKWISE_90;
         });
      }
      if (state.getBlock() instanceof StairBlock) {
         return rotateStairs(state, axis, normalized);
      }
      BlockState rotated = state;
      for (Property<?> property : state.getProperties()) {
         rotated = rotateProperty(rotated, property, axis, normalized);
      }
      return rotated;
   }

   private static <T extends Comparable<T>> BlockState rotateProperty(
      BlockState state, Property<T> property, AxisGizmo.Axis axis, int turns
   ) {
      T value = state.getValue(property);
      String target;
      if (value instanceof Direction direction) {
         target = rotateDirection(direction, axis, turns).getSerializedName();
      } else if (value instanceof Direction.Axis directionAxis) {
         Direction direction = Direction.fromAxisAndDirection(directionAxis, Direction.AxisDirection.POSITIVE);
         target = rotateDirection(direction, axis, turns).getAxis().getSerializedName();
      } else {
         return state;
      }
      return property.getValue(target).map(candidate -> state.setValue(property, candidate)).orElse(state);
   }

   private static Direction rotateDirection(Direction direction, AxisGizmo.Axis axis, int turns) {
      BlockPos vector = rotateVector(direction.getStepX(), direction.getStepY(), direction.getStepZ(), axis, turns);
      return Direction.fromDelta(vector.getX(), vector.getY(), vector.getZ());
   }

   /** Reuses the cell transform so state and position cannot disagree about axis signs. */
   private static BlockPos rotateVector(int x, int y, int z, AxisGizmo.Axis axis, int turns) {
      Vec3 rotated = VoxelRotation.rotatePoint(new Vec3(x, y, z), Vec3.ZERO, axis, turns * Math.PI * 0.5);
      return new BlockPos((int)Math.round(rotated.x), (int)Math.round(rotated.y), (int)Math.round(rotated.z));
   }

   private static BlockState rotateStairs(BlockState state, AxisGizmo.Axis axis, int turns) {
      return transformStairs(state, vector -> rotateVector(vector.getX(), vector.getY(), vector.getZ(), axis, turns),
         rotateDirection(state.getValue(StairBlock.FACING), axis, turns));
   }

   private static BlockState transformStairs(BlockState state, Function<BlockPos, BlockPos> transform, Direction preferredFacing) {
      int mask = stairMask(state);
      int rotatedMask = 0;
      for (int octant = 0; octant < 8; octant++) {
         if ((mask & (1 << octant)) == 0) {
            continue;
         }
         BlockPos rotated = transform.apply(new BlockPos(
            (octant & 1) == 0 ? -1 : 1,
            (octant & 2) == 0 ? -1 : 1,
            (octant & 4) == 0 ? -1 : 1
         ));
         int target = (rotated.getX() > 0 ? 1 : 0)
            | (rotated.getY() > 0 ? 2 : 0) | (rotated.getZ() > 0 ? 4 : 0);
         rotatedMask |= 1 << target;
      }
      List<BlockState> orientations = STAIR_ORIENTATIONS.computeIfAbsent(state.getBlock(), BlockStateRotation::stairOrientations)
         .get(rotatedMask);
      if (orientations == null) {
         return state;
      }
      BlockState orientation = orientations.stream().filter(candidate -> candidate.getValue(StairBlock.FACING) == preferredFacing)
         .findFirst().orElse(orientations.getFirst());
      return state.setValue(StairBlock.FACING, orientation.getValue(StairBlock.FACING))
         .setValue(StairBlock.HALF, orientation.getValue(StairBlock.HALF))
         .setValue(StairBlock.SHAPE, orientation.getValue(StairBlock.SHAPE));
   }

   private static Map<Integer, List<BlockState>> stairOrientations(Block block) {
      Map<Integer, List<BlockState>> orientations = new HashMap<>();
      for (Direction facing : StairBlock.FACING.getPossibleValues()) {
         for (Half half : Half.values()) {
            for (StairsShape shape : StairsShape.values()) {
               BlockState candidate = block.defaultBlockState().setValue(StairBlock.FACING, facing)
                  .setValue(StairBlock.HALF, half).setValue(StairBlock.SHAPE, shape);
               orientations.computeIfAbsent(stairMask(candidate), ignored -> new ArrayList<>()).add(candidate);
            }
         }
      }
      orientations.replaceAll((mask, candidates) -> List.copyOf(candidates));
      return Map.copyOf(orientations);
   }

   /** Vanilla stairs occupy a subset of the eight half-block cubes. */
   private static int stairMask(BlockState state) {
      var boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
      int mask = 0;
      for (int octant = 0; octant < 8; octant++) {
         double x = (octant & 1) == 0 ? 0.25 : 0.75;
         double y = (octant & 2) == 0 ? 0.25 : 0.75;
         double z = (octant & 4) == 0 ? 0.25 : 0.75;
         for (var box : boxes) {
            if (box.contains(x, y, z)) {
               mask |= 1 << octant;
               break;
            }
         }
      }
      return mask;
   }
}
