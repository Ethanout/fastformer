package io.github.fastformer.fastplace.interaction;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Changes one half-block corner, using only shapes that the material family can represent. */
final class TinkerShapeTransform {
   private static final double SURFACE_OFFSET = 1.0E-4;
   private TinkerShapeTransform() {}

   static BlockState apply(TinkerShapeFamily family, BlockState state, BlockHitResult hit) {
      if (family.stairs().isEmpty()) return slabOnly(family, state, hit);
      List<BlockState> candidates = stairStates(family.stairs().orElseThrow(), state);
      candidates.add(family.full().withPropertiesOf(state));
      family.slab().ifPresent(slab -> {
         candidates.add(slab.withPropertiesOf(state).setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM));
         candidates.add(slab.withPropertiesOf(state).setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
      });
      return changeCorner(state, hit, candidates);
   }

   static BlockState stairsOnly(BlockState state, BlockHitResult hit) {
      return changeCorner(state, hit, stairStates(state.getBlock(), state));
   }

   private static List<BlockState> stairStates(Block stairs, BlockState source) {
      List<BlockState> result = new ArrayList<>();
      BlockState base = stairs.withPropertiesOf(source);
      for (Half half : Half.values()) {
         for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (StairsShape shape : StairsShape.values()) {
               result.add(base.setValue(BlockStateProperties.HALF, half)
                  .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                  .setValue(BlockStateProperties.STAIRS_SHAPE, shape));
            }
         }
      }
      return result;
   }

   private static BlockState changeCorner(BlockState state, BlockHitResult hit, List<BlockState> candidates) {
      int occupied = occupancy(state);
      Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getBlockPos()));
      Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
      Vec3 outside = local.add(normal.scale(SURFACE_OFFSET));
      boolean fill = inside(outside);
      Vec3 cell = fill ? outside : local.subtract(normal.scale(SURFACE_OFFSET));
      if (!inside(cell)) return state;
      int bit = 1 << ((cell.x >= 0.5 ? 1 : 0) | (cell.z >= 0.5 ? 2 : 0) | (cell.y >= 0.5 ? 4 : 0));
      int target = fill ? occupied | bit : occupied & ~bit;
      if (target == occupied) return state;
      BlockState best = state;
      int bestCost = Integer.MAX_VALUE;
      for (BlockState candidate : candidates) {
         if (occupancy(candidate) != target) continue;
         int cost = orientationCost(state, candidate);
         if (cost < bestCost) {
            best = candidate;
            bestCost = cost;
         }
      }
      return best;
   }

   static int occupancy(BlockState state) {
      var boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
      int mask = 0;
      for (int cell = 0; cell < 8; cell++) {
         double x = (cell & 1) == 0 ? 0.25 : 0.75;
         double z = (cell & 2) == 0 ? 0.25 : 0.75;
         double y = (cell & 4) == 0 ? 0.25 : 0.75;
         if (boxes.stream().anyMatch(box -> box.contains(x, y, z))) mask |= 1 << cell;
      }
      return mask;
   }

   private static boolean inside(Vec3 point) {
      return point.x >= 0 && point.x < 1 && point.y >= 0 && point.y < 1 && point.z >= 0 && point.z < 1;
   }

   private static int orientationCost(BlockState source, BlockState candidate) {
      if (!(source.getBlock() instanceof StairBlock) || !(candidate.getBlock() instanceof StairBlock)) return 0;
      int half = source.getValue(BlockStateProperties.HALF) == candidate.getValue(BlockStateProperties.HALF) ? 0 : 4;
      int facing = source.getValue(BlockStateProperties.HORIZONTAL_FACING) == candidate.getValue(BlockStateProperties.HORIZONTAL_FACING) ? 0 : 1;
      return half + facing;
   }

   private static BlockState slabOnly(TinkerShapeFamily family, BlockState state, BlockHitResult hit) {
      if (state.getBlock() instanceof SlabBlock && state.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE) {
         return hit.getDirection().getAxis().isVertical() ? family.full().withPropertiesOf(state) : state;
      }
      boolean top = hit.getDirection() == Direction.DOWN || (hit.getDirection().getAxis().isHorizontal()
         && hit.getLocation().y - hit.getBlockPos().getY() < 0.5);
      return family.slab().map(slab -> slab.withPropertiesOf(state)
         .setValue(BlockStateProperties.SLAB_TYPE, top ? SlabType.TOP : SlabType.BOTTOM)).orElse(state);
   }
}
