package io.github.fastformer.common.tinker.shape;

import io.github.fastformer.common.tinker.TinkerAction;
import io.github.fastformer.common.tinker.TinkerContext;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.phys.BlockHitResult;

/** Surface-local rules that precede the full-block generic fallback. */
public final class TinkerSurfaceRules {
   private TinkerSurfaceRules() {}

   public static Optional<TinkerAction> apply(TinkerContext context) {
      SpecialResult result = specialized(context.level(), context.pos(), context.state(), context.block(), context.face(), context.hit());
      if (!result.handled()) return Optional.empty();
      return Optional.of(result.state() == null ? TinkerAction.PASS
         : TinkerAction.edit(context.level(), context.pos(), result.state()));
   }

   private static SpecialResult specialized(
      Level level, BlockPos pos, BlockState state, Block block, Direction face,
      BlockHitResult hit
   ) {
      if (block instanceof CarvedPumpkinBlock && face.getAxis().isHorizontal()) {
         return handled(state.setValue(BlockStateProperties.HORIZONTAL_FACING, face));
      }
      if (block instanceof SlabBlock) {
         SlabType type = state.getValue(BlockStateProperties.SLAB_TYPE);
         if (type == SlabType.DOUBLE) return SpecialResult.REJECTED;
         return handled(state);
      }
      if (block instanceof StairBlock) return handled(TinkerShapeTransform.stairsOnly(state, hit));
      if (block instanceof FenceBlock || block instanceof IronBarsBlock || block instanceof CrossCollisionBlock) {
         BooleanProperty property = connectingProperty(ConnectionHitDirection.resolve(hit), false);
         return handled(property != null && state.hasProperty(property) ? state.cycle(property) : state);
      }
      if (block instanceof HugeMushroomBlock) {
         BooleanProperty property = connectingProperty(face, true);
         return handled(property != null && state.hasProperty(property) ? state.cycle(property) : state);
      }
      if (block instanceof WallBlock) {
         var local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
         // The central post spans x/z 4..12 in the vanilla wall shape.
         boolean onPost = Math.abs(local.x - 0.5) <= 0.25 + 1.0E-7
            && Math.abs(local.z - 0.5) <= 0.25 + 1.0E-7;
         if (onPost && state.getValue(BlockStateProperties.UP)) return handled(state.cycle(BlockStateProperties.UP));
         Direction connection = ConnectionHitDirection.resolve(hit);
         if (connection == Direction.UP) return handled(state.cycle(BlockStateProperties.UP));
         EnumProperty<WallSide> property = wallProperty(connection);
         if (property != null && state.hasProperty(property)) {
            WallSide current = state.getValue(property);
            return handled(state.setValue(property, current == WallSide.NONE ? WallSide.LOW
               : current == WallSide.LOW ? WallSide.TALL : WallSide.NONE));
         }
      }
      if (block instanceof RedStoneWireBlock) {
         Direction operation = TinkerSurfaceHit.sideOperation(hit);
         if (operation.getAxis().isVertical()) {
            boolean dot = horizontalRedstoneProperties().stream()
               .noneMatch(property -> state.getValue(property) == RedstoneSide.SIDE);
            BlockState result = state;
            for (EnumProperty<RedstoneSide> property : horizontalRedstoneProperties()) {
               result = result.setValue(property, state.getValue(property) == RedstoneSide.UP ? RedstoneSide.UP
                  : dot ? RedstoneSide.SIDE : RedstoneSide.NONE);
            }
            return handled(result);
         }
         EnumProperty<RedstoneSide> property = redstoneProperty(operation);
         if (property != null) {
            RedstoneSide current = state.getValue(property);
            return handled(state.setValue(property, current == RedstoneSide.NONE ? RedstoneSide.SIDE
               : current == RedstoneSide.SIDE ? RedstoneSide.UP : RedstoneSide.NONE));
         }
      }
      if (block instanceof FenceGateBlock) {
         BlockState changed = TinkerFenceGate.apply(state, hit, state.getShape(level, pos).bounds());
         return changed == null ? SpecialResult.REJECTED : handled(changed);
      }
      if (block instanceof ButtonBlock || block instanceof LeverBlock) return SpecialResult.REJECTED;
      if (block instanceof BaseRailBlock) return handled(TinkerRail.apply(state, hit));
      if (block == Blocks.NOTE_BLOCK) return SpecialResult.REJECTED;
      return SpecialResult.NOT_MATCHED;
   }

   private static SpecialResult handled(BlockState state) { return new SpecialResult(true, state); }

   private static BooleanProperty connectingProperty(Direction direction, boolean vertical) {
      return switch (direction) {
         case NORTH -> BlockStateProperties.NORTH;
         case EAST -> BlockStateProperties.EAST;
         case SOUTH -> BlockStateProperties.SOUTH;
         case WEST -> BlockStateProperties.WEST;
         case UP -> vertical ? BlockStateProperties.UP : null;
         case DOWN -> vertical ? BlockStateProperties.DOWN : null;
      };
   }

   private static EnumProperty<WallSide> wallProperty(Direction direction) {
      return switch (direction) {
         case NORTH -> BlockStateProperties.NORTH_WALL;
         case EAST -> BlockStateProperties.EAST_WALL;
         case SOUTH -> BlockStateProperties.SOUTH_WALL;
         case WEST -> BlockStateProperties.WEST_WALL;
         default -> null;
      };
   }

   private static EnumProperty<RedstoneSide> redstoneProperty(Direction direction) {
      return switch (direction) {
         case NORTH -> BlockStateProperties.NORTH_REDSTONE;
         case EAST -> BlockStateProperties.EAST_REDSTONE;
         case SOUTH -> BlockStateProperties.SOUTH_REDSTONE;
         case WEST -> BlockStateProperties.WEST_REDSTONE;
         default -> null;
      };
   }

   private static List<EnumProperty<RedstoneSide>> horizontalRedstoneProperties() {
      return List.of(BlockStateProperties.NORTH_REDSTONE, BlockStateProperties.EAST_REDSTONE,
         BlockStateProperties.SOUTH_REDSTONE, BlockStateProperties.WEST_REDSTONE);
   }

   private record SpecialResult(boolean handled, BlockState state) {
      private static final SpecialResult NOT_MATCHED = new SpecialResult(false, null);
      private static final SpecialResult REJECTED = new SpecialResult(true, null);
   }
}
