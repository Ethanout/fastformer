package io.github.fastformer.fastplace;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.phys.BlockHitResult;

/** Axiom-style empty-hand block manipulation with type-specific semantics. */
final class BlockTinker {
   private static final List<Property<?>> GENERIC_FALLBACK = List.of(
      BlockStateProperties.FACING,
      BlockStateProperties.HORIZONTAL_FACING,
      BlockStateProperties.AXIS,
      BlockStateProperties.HORIZONTAL_AXIS,
      BlockStateProperties.HALF,
      BlockStateProperties.ROTATION_16,
      BlockStateProperties.OPEN,
      BlockStateProperties.LIT,
      BlockStateProperties.POWERED,
      BlockStateProperties.WATERLOGGED
   );

   private BlockTinker() {
   }

   static boolean use(ServerPlayer player, BlockHitResult hit) {
      if (player == null || hit == null || !player.isCreative() || !player.getMainHandItem().isEmpty()
         || WorldHistoryManager.busy(player) || FastPlaceManager.active(player)
         || OperationManager.active(player) || GeometryManager.active(player)
         || FastPlaceManager.taskActive(player) || OperationManager.taskActive(player)) {
         return false;
      }
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      if (!settings.enabled() || !settings.emptyHandWrench()) {
         return false;
      }
      ServerLevel level = player.serverLevel();
      Map<BlockPos, BlockState> changes = resolve(level, hit);
      if (changes.isEmpty() || !WorldWriteCoordinator.tryAcquire(player.getServer(), level.dimension(), player.getUUID())) {
         return false;
      }
      try {
         return apply(player, level, changes, settings.placementUpdateMode().flags());
      } finally {
         WorldWriteCoordinator.release(player.getServer(), level.dimension(), player.getUUID());
      }
   }

   static Map<BlockPos, BlockState> resolve(ServerLevel level, BlockHitResult hit) {
      BlockPos pos = hit.getBlockPos();
      BlockState state = level.getBlockState(pos);
      Block block = state.getBlock();
      Direction face = hit.getDirection();
      LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
      SpecialResult special = specialized(level, pos, state, block, face, hit, changes);
      if (special.handled() && special.state() == null) {
         return Map.of();
      }
      BlockState changed = special.handled() ? special.state() : generic(state, face);
      if (changed != null && !changed.equals(state)) {
         changes.put(pos.immutable(), changed);
      }
      changes.entrySet().removeIf(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue()));
      return Map.copyOf(changes);
   }

   private static SpecialResult specialized(
      ServerLevel level, BlockPos pos, BlockState state, Block block, Direction face,
      BlockHitResult hit, Map<BlockPos, BlockState> extra
   ) {
      if (block instanceof PistonBaseBlock && state.hasProperty(BlockStateProperties.FACING)) {
         return handled(state.setValue(BlockStateProperties.FACING, face));
      }
      if (block instanceof AbstractFurnaceBlock && face.getAxis().isHorizontal()) {
         Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
         return handled(facing == face && state.hasProperty(BlockStateProperties.LIT)
            ? state.cycle(BlockStateProperties.LIT)
            : state.setValue(BlockStateProperties.HORIZONTAL_FACING, face));
      }
      if (block instanceof CarvedPumpkinBlock && face.getAxis().isHorizontal()) {
         return handled(state.setValue(BlockStateProperties.HORIZONTAL_FACING, face));
      }
      if (block instanceof TrapDoorBlock) {
         return block == Blocks.IRON_TRAPDOOR
            ? handled(state.cycle(BlockStateProperties.HALF)) : SpecialResult.REJECTED;
      }
      if (block instanceof DoorBlock) {
         if (DoorBlock.isWoodenDoor(state)) {
            return SpecialResult.REJECTED;
         }
         boolean open = !state.getValue(BlockStateProperties.OPEN);
         Direction otherDirection = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
            ? Direction.UP : Direction.DOWN;
         BlockPos otherPos = pos.relative(otherDirection);
         BlockState other = level.getBlockState(otherPos);
         if (other.getBlock() instanceof DoorBlock && other.hasProperty(BlockStateProperties.OPEN)) {
            extra.put(otherPos.immutable(), other.setValue(BlockStateProperties.OPEN, open));
         }
         return handled(state.setValue(BlockStateProperties.OPEN, open));
      }
      if (block instanceof SlabBlock) {
         SlabType type = state.getValue(BlockStateProperties.SLAB_TYPE);
         if (type == SlabType.DOUBLE) {
            return SpecialResult.REJECTED;
         }
         double relativeY = hit.getLocation().y - pos.getY();
         return handled(state.setValue(BlockStateProperties.SLAB_TYPE, relativeY > 0.5 ? SlabType.TOP : SlabType.BOTTOM));
      }
      if (block instanceof StairBlock) {
         if (face.getAxis().isVertical()) {
            return handled(state.setValue(BlockStateProperties.HALF, face == Direction.UP ? Half.BOTTOM : Half.TOP));
         }
         return handled(state.setValue(BlockStateProperties.HORIZONTAL_FACING, face));
      }
      if (block instanceof BarrelBlock) {
         Direction facing = state.getValue(BlockStateProperties.FACING);
         return handled(facing == face && state.hasProperty(BlockStateProperties.OPEN)
            ? state.cycle(BlockStateProperties.OPEN)
            : state.setValue(BlockStateProperties.FACING, face));
      }
      if (block instanceof ChestBlock && face.getAxis().isHorizontal()) {
         return handled(state.setValue(BlockStateProperties.HORIZONTAL_FACING, face));
      }
      if (block instanceof FenceBlock || block instanceof IronBarsBlock || block instanceof CrossCollisionBlock) {
         BooleanProperty property = connectingProperty(face, false);
         return handled(property != null && state.hasProperty(property) ? state.cycle(property) : state);
      }
      if (block instanceof HugeMushroomBlock) {
         BooleanProperty property = connectingProperty(face, true);
         return handled(property != null && state.hasProperty(property) ? state.cycle(property) : state);
      }
      if (block instanceof WallBlock) {
         if (face == Direction.UP) {
            return handled(state.cycle(BlockStateProperties.UP));
         }
         EnumProperty<WallSide> property = wallProperty(face);
         if (property != null && state.hasProperty(property)) {
            WallSide current = state.getValue(property);
            return handled(state.setValue(property, current == WallSide.NONE ? WallSide.LOW
               : current == WallSide.LOW ? WallSide.TALL : WallSide.NONE));
         }
      }
      if (block instanceof RedStoneWireBlock) {
         if (face == Direction.UP) {
            boolean dot = horizontalRedstoneProperties().stream()
               .allMatch(property -> state.getValue(property) == RedstoneSide.NONE);
            BlockState result = state;
            for (EnumProperty<RedstoneSide> property : horizontalRedstoneProperties()) {
               result = result.setValue(property, dot ? RedstoneSide.SIDE : RedstoneSide.NONE);
            }
            return handled(result);
         }
         EnumProperty<RedstoneSide> property = redstoneProperty(face);
         if (property != null) {
            RedstoneSide current = state.getValue(property);
            return handled(state.setValue(property, current == RedstoneSide.NONE ? RedstoneSide.SIDE
               : current == RedstoneSide.SIDE ? RedstoneSide.UP : RedstoneSide.NONE));
         }
      }
      if (block instanceof FenceGateBlock) {
         if (face == Direction.UP && state.hasProperty(BlockStateProperties.IN_WALL)) {
            return handled(state.cycle(BlockStateProperties.IN_WALL));
         }
         if (face.getAxis().isHorizontal()) {
            return handled(state.setValue(BlockStateProperties.HORIZONTAL_FACING, face));
         }
      }
      if (block instanceof ButtonBlock || block instanceof LeverBlock) {
         return SpecialResult.REJECTED;
      }
      if (block instanceof BaseRailBlock) {
         Property<?> property = state.hasProperty(BlockStateProperties.RAIL_SHAPE)
            ? BlockStateProperties.RAIL_SHAPE : BlockStateProperties.RAIL_SHAPE_STRAIGHT;
         return handled(cycle(state, property));
      }
      return SpecialResult.NOT_MATCHED;
   }

   private static BlockState generic(BlockState state, Direction face) {
      if (state.hasProperty(BlockStateProperties.FACING)) {
         return state.setValue(BlockStateProperties.FACING, face);
      }
      if (face.getAxis().isHorizontal() && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
         return state.setValue(BlockStateProperties.HORIZONTAL_FACING, face);
      }
      for (Property<?> property : GENERIC_FALLBACK) {
         if (state.hasProperty(property)) {
            return cycle(state, property);
         }
      }
      return state;
   }

   private static boolean apply(ServerPlayer player, ServerLevel level, Map<BlockPos, BlockState> changes, int flags) {
      ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>();
      LinkedHashMap<BlockPos, ReversibleBlockSnapshot> after = new LinkedHashMap<>();
      for (BlockPos pos : changes.keySet()) {
         Optional<ReversibleBlockSnapshot> snapshot = ReversibleBlockSnapshot.capture(level, pos);
         if (snapshot.isEmpty()) {
            return false;
         }
         before.addFirst(snapshot.orElseThrow());
      }
      for (var entry : changes.entrySet()) {
         if (!WorldWriteSideEffectGuard.setBlock(level, entry.getKey(), entry.getValue(), flags)) {
            rollback(level, before);
            return false;
         }
         Optional<ReversibleBlockSnapshot> snapshot = ReversibleBlockSnapshot.capture(level, entry.getKey());
         if (snapshot.isEmpty()) {
            rollback(level, before);
            return false;
         }
         after.put(entry.getKey(), snapshot.orElseThrow());
      }
      if (!WorldHistoryManager.record(player, level, before, after)) {
         rollback(level, before);
         return false;
      }
      return true;
   }

   private static void rollback(ServerLevel level, ArrayDeque<ReversibleBlockSnapshot> before) {
      before.forEach(snapshot -> snapshot.restore(level, PlacementUpdateMode.CLIENT_ONLY.flags()));
   }

   private static SpecialResult handled(BlockState state) {
      return new SpecialResult(true, state);
   }

   @SuppressWarnings({"rawtypes", "unchecked"})
   private static BlockState cycle(BlockState state, Property property) {
      return state.cycle(property);
   }

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
