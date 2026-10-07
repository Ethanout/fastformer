package io.github.fastformer.fastplace.interaction;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;

/** Coordinated structures produce one map for the shared undoable transaction. */
final class TinkerStructureRules {
   private TinkerStructureRules() {}

   static TinkerAction apply(Level level, BlockState state, BlockHitResult hit, boolean shift) {
      BlockPos pos = hit.getBlockPos();
      Direction face = hit.getDirection();
      Block block = state.getBlock();
      if (block instanceof PistonBaseBlock) return piston(level, pos, state);
      if (block instanceof ChestBlock) return shift ? chest(level, state, hit) : TinkerAction.PASS;
      if (block instanceof BarrelBlock) {
         if (face == state.getValue(BlockStateProperties.FACING)) return TinkerAction.edit(level, pos, state.cycle(BlockStateProperties.OPEN));
         return shift ? TinkerAction.edit(level, pos, state.setValue(BlockStateProperties.FACING, face)) : TinkerAction.PASS;
      }
      if (block instanceof AbstractFurnaceBlock) {
         Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
         if (face == facing) return TinkerAction.edit(level, pos, state.cycle(BlockStateProperties.LIT));
         if (!shift) return TinkerAction.PASS;
         return TinkerAction.edit(level, pos, face.getAxis().isHorizontal()
            ? state.setValue(BlockStateProperties.HORIZONTAL_FACING, face) : state);
      }
      if (block instanceof TrapDoorBlock) return block == Blocks.IRON_TRAPDOOR
         ? TinkerAction.edit(level, pos, state.cycle(BlockStateProperties.OPEN)) : TinkerAction.PASS;
      if (block instanceof DoorBlock) return block == Blocks.IRON_DOOR
         ? doorChange(level, pos, state, state.cycle(BlockStateProperties.OPEN)) : TinkerAction.PASS;
      return null;
   }

   static TinkerAction doorChange(Level level, BlockPos pos, BlockState before, BlockState changed) {
      BlockPos otherPos = before.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
      BlockState other = level.getBlockState(otherPos);
      Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
      changes.put(pos, changed);
      if (other.is(before.getBlock()) && other.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
         != before.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
         BlockState companion = changed.getBlock().withPropertiesOf(other);
         if (changed.getValue(BlockStateProperties.OPEN) != before.getValue(BlockStateProperties.OPEN)) {
            companion = companion.setValue(BlockStateProperties.OPEN, changed.getValue(BlockStateProperties.OPEN));
         }
         changes.put(otherPos, companion);
      }
      return TinkerAction.edit(level, changes);
   }

   private static TinkerAction piston(Level level, BlockPos pos, BlockState state) {
      Direction facing = state.getValue(BlockStateProperties.FACING);
      BlockPos headPos = pos.relative(facing);
      if (!level.hasChunkAt(headPos) || level.isOutsideBuildHeight(headPos)) return TinkerAction.NO_CHANGE;
      BlockState head = level.getBlockState(headPos);
      boolean extended = state.getValue(BlockStateProperties.EXTENDED);
      Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
      if (!extended && !head.isAir()) return TinkerAction.NO_CHANGE;
      changes.put(pos, state.setValue(BlockStateProperties.EXTENDED, !extended));
      if (!extended) {
         changes.put(headPos, Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING, facing)
            .setValue(BlockStateProperties.PISTON_TYPE, state.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT));
      } else if (head.getBlock() instanceof PistonHeadBlock && head.getValue(BlockStateProperties.FACING) == facing) {
         changes.put(headPos, Blocks.AIR.defaultBlockState());
      }
      return TinkerAction.edit(level, changes);
   }

   private static TinkerAction chest(Level level, BlockState state, BlockHitResult hit) {
      BlockPos pos = hit.getBlockPos();
      ChestType type = state.getValue(BlockStateProperties.CHEST_TYPE);
      Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
      if (type != ChestType.SINGLE) {
         BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
         if (!level.hasChunkAt(otherPos)) return TinkerAction.NO_CHANGE;
         BlockState other = level.getBlockState(otherPos);
         changes.put(pos, state.setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE));
         if (other.is(state.getBlock()) && other.getValue(BlockStateProperties.CHEST_TYPE) == type.getOpposite()
            && other.getValue(BlockStateProperties.HORIZONTAL_FACING) == state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            changes.put(otherPos, other.setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE));
         }
      } else {
         Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
         Direction side = TinkerSurfaceHit.sideOperation(hit);
         if (!side.getAxis().isHorizontal() || side.getAxis() == facing.getAxis()) return TinkerAction.NO_CHANGE;
         BlockPos otherPos = pos.relative(side);
         if (!level.hasChunkAt(otherPos)) return TinkerAction.NO_CHANGE;
         BlockState other = level.getBlockState(otherPos);
         if (!other.is(state.getBlock()) || other.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE
            || other.getValue(BlockStateProperties.HORIZONTAL_FACING) != facing) return TinkerAction.NO_CHANGE;
         ChestType target = side == facing.getClockWise() ? ChestType.LEFT : ChestType.RIGHT;
         changes.put(pos, state.setValue(BlockStateProperties.CHEST_TYPE, target));
         changes.put(otherPos, other.setValue(BlockStateProperties.CHEST_TYPE, target.getOpposite()));
      }
      return TinkerAction.edit(level, changes);
   }
}
