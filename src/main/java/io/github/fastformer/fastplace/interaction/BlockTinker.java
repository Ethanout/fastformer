package io.github.fastformer.fastplace.interaction;

import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.server.session.FastPlaceManager;
import io.github.fastformer.server.session.GeometryManager;
import io.github.fastformer.server.session.OperationManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
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
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.phys.BlockHitResult;

/** Axiom-style empty-hand block manipulation with type-specific semantics. */
public final class BlockTinker {
   private static final List<Property<?>> GENERIC_FALLBACK = List.of(
      BlockStateProperties.OPEN,
      BlockStateProperties.LIT,
      BlockStateProperties.POWERED
   );

   private BlockTinker() {
   }

   public static boolean use(ServerPlayer player, BlockHitResult hit) {
      if (!canEdit(player) || !withinInteractionRange(player, hit)
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
      TinkerAction action = previewAction(player, hit);
      if (!action.consumed()) return false;
      if (action.changes().isEmpty()) return true;
      boolean wax = action.changes().entrySet().stream()
         .anyMatch(entry -> TinkerCopperCycle.addsWax(level.getBlockState(entry.getKey()), entry.getValue()));
      var metadata = io.github.fastformer.fastplace.history.BlockEditHistoryMetadata.fromInteraction(player, hit, action.changes(), settings.tinkerHistoryMergeLimits());
      var outcome = tinker(player, level, action.changes(), settings.placementUpdateMode().flags(), metadata);
      if (outcome == BlockEditTransaction.Outcome.APPLIED && wax) level.levelEvent(null, 3003, hit.getBlockPos(), 0);
      return outcome.consumedInteraction() || outcome == BlockEditTransaction.Outcome.REJECTED_BUSY;
   }

   /**
    * Applies the tinkered states through the shared short transaction.
    *
    * <p>The result separates "this block has no tinker change" from "the change
    * failed". A failed change reports itself here, and never continues with the
    * default game interaction as if the tinker did not apply.</p>
    */
   static BlockEditTransaction.Outcome tinker(
      ServerPlayer player, ServerLevel level, Map<BlockPos, BlockState> changes, int flags
   ) {
      return tinker(player, level, changes, flags, null);
   }

   private static BlockEditTransaction.Outcome tinker(ServerPlayer player, ServerLevel level,
      Map<BlockPos, BlockState> changes, int flags, io.github.fastformer.fastplace.history.BlockEditHistoryMetadata metadata) {
      BlockEditTransaction.Outcome outcome = BlockEditTransaction.apply(
         player, level, changes, flags, PlacementUpdateMode.CLIENT_ONLY.flags(), metadata
      );
      BlockEditTransaction.report(outcome, player);
      return outcome;
   }

   /** Reads the same target and conversion on both sides, without writing to the world. */
   public static Map<BlockPos, BlockState> preview(Player player, BlockHitResult hit) {
      return previewAction(player, hit).changes();
   }

   public static boolean consumes(Player player, BlockHitResult hit) {
      return previewAction(player, hit).consumed();
   }

   public static boolean canEdit(Player player) {
      return player != null && player.isCreative() && player.getMainHandItem().isEmpty();
   }

   public static boolean withinInteractionRange(Player player, BlockHitResult hit) {
      return player != null && hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
         && !io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast.beyondInteractionRange(player, hit);
   }

   private static TinkerAction previewAction(Player player, BlockHitResult hit) {
      if (!canEdit(player) || !withinInteractionRange(player, hit)) return TinkerAction.PASS;
      return resolveAction(player.level(), hit, player.isShiftKeyDown());
   }

   static Map<BlockPos, BlockState> resolve(Level level, BlockHitResult hit) {
      return resolveAction(level, hit, false).changes();
   }

   static TinkerAction resolveAction(Level level, BlockHitResult hit, boolean shift) {
      BlockPos pos = hit.getBlockPos();
      BlockState state = level.getBlockState(pos);
      Block block = state.getBlock();
      Direction face = hit.getDirection();
      LinkedHashMap<BlockPos, BlockState> changes = new LinkedHashMap<>();
      var override = TinkerPropertyRule.find(level.registryAccess(), block, shift);
      if (override.isPresent()) return override.orElseThrow().apply(level, state, hit);
      BlockState copper = TinkerCopperCycle.apply(state, hit, shift);
      if (copper != null) return block instanceof DoorBlock
         ? TinkerStructureRules.doorChange(level, pos, state, copper) : TinkerAction.edit(level, pos, copper);
      TinkerAction structure = TinkerStructureRules.apply(level, state, hit, shift);
      if (structure != null) return structure;
      BlockState property = TinkerStateRules.apply(state, hit, shift);
      if (property != null) return TinkerAction.edit(level, pos, property);
      // Unclaimed Shift clicks retain vanilla behavior; copper overrides have already run.
      if (shift) return TinkerAction.PASS;
      BlockState ground = TinkerGroundCycle.apply(state, face);
      if (ground != null) return TinkerAction.edit(level, pos, ground);
      BlockState torch = TinkerTorchCycle.apply(state);
      if (torch != null) return TinkerAction.edit(level, pos, torch);
      var wood = TinkerWoodFamily.find(level.registryAccess(), block);
      if (wood.isPresent()) {
         BlockState changed = wood.orElseThrow().apply(state, hit);
         return TinkerAction.edit(level, pos, changed);
      }
      var family = TinkerShapeFamilies.find(level.registryAccess(), block);
      if (family.isPresent() && !state.hasBlockEntity()) {
         BlockState changed = TinkerShapeTransform.apply(family.orElseThrow(), state, hit);
         if (changed.equals(state) && TinkerCopperCycle.supports(state)) changed = TinkerCopperCycle.combined(state);
         return TinkerAction.edit(level, pos, changed);
      }
      SpecialResult special = specialized(level, pos, state, block, face, hit);
      if (special.handled() && special.state() == null) {
         return TinkerAction.PASS;
      }
      BlockState changed = special.handled() ? special.state()
         : Block.isShapeFullBlock(state.getShape(level, pos)) && !state.hasBlockEntity() ? generic(state) : state;
      if (changed != null && !changed.equals(state)) {
         changes.put(pos.immutable(), changed);
      }
      changes.entrySet().removeIf(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue()));
      return new TinkerAction(special.handled() || !changes.isEmpty(), Map.copyOf(changes));
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
         if (type == SlabType.DOUBLE) {
            return SpecialResult.REJECTED;
         }
         return handled(state);
      }
      if (block instanceof StairBlock) {
         return handled(TinkerShapeTransform.stairsOnly(state, hit));
      }
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
         if (connection == Direction.UP) {
            return handled(state.cycle(BlockStateProperties.UP));
         }
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
      if (block instanceof ButtonBlock || block instanceof LeverBlock) {
         return SpecialResult.REJECTED;
      }
      if (block instanceof BaseRailBlock) {
         return handled(TinkerRail.apply(state, hit));
      }
      if (block == Blocks.NOTE_BLOCK) return SpecialResult.REJECTED;
      return SpecialResult.NOT_MATCHED;
   }

   private static BlockState generic(BlockState state) {
      for (Property<?> property : GENERIC_FALLBACK) {
         if (state.hasProperty(property)) {
            return cycle(state, property);
         }
      }
      return state;
   }

   private static SpecialResult handled(BlockState state) {
      return new SpecialResult(true, state);
   }

   private static <T extends Comparable<T>> BlockState cycle(BlockState state, Property<T> property) {
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
