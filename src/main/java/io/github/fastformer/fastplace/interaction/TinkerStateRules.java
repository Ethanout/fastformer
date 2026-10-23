package io.github.fastformer.fastplace.interaction;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;

/** Single-cell property edits. Null means this module has no rule for the block. */
final class TinkerStateRules {
   private TinkerStateRules() {}

   static BlockState apply(BlockState state, BlockHitResult hit, boolean shift, boolean skipRepeatedCropStages) {
      Block block = state.getBlock();
      Direction side = TinkerSurfaceHit.sideOperation(hit);
      if (block instanceof SeaPickleBlock) return adjust(state, BlockStateProperties.PICKLES, side);
      if (block instanceof SnowLayerBlock) return adjust(state, BlockStateProperties.LAYERS, side);
      if (block instanceof TurtleEggBlock) return shift ? state.cycle(BlockStateProperties.HATCH)
         : adjust(state, BlockStateProperties.EGGS, side);
      if (block instanceof SnifferEggBlock) return state.cycle(BlockStateProperties.HATCH);
      if (block instanceof PinkPetalsBlock) return state.cycle(BlockStateProperties.FLOWER_AMOUNT);
      if (block instanceof CandleBlock) return candle(state);
      if (block instanceof CandleCakeBlock || block instanceof CampfireBlock) return light(state);
      if (block instanceof CakeBlock) return state.cycle(BlockStateProperties.BITES);
      if (block instanceof ComposterBlock) return state.getValue(BlockStateProperties.LEVEL_COMPOSTER) == 8
         ? state.setValue(BlockStateProperties.LEVEL_COMPOSTER, 0) : adjust(state, BlockStateProperties.LEVEL_COMPOSTER, side);
      if (block instanceof PointedDripstoneBlock) return orderedThickness(state);
      if (block instanceof SweetBerryBushBlock) return state.cycle(BlockStateProperties.AGE_3);
      if (block instanceof CaveVines) return state.cycle(BlockStateProperties.BERRIES);
      BlockState stem = TinkerStemCycle.apply(state, hit);
      if (stem != null) return stem;
      if (block instanceof CropBlock || block instanceof NetherWartBlock
         || block instanceof CocoaBlock || block instanceof PitcherCropBlock) {
         IntegerProperty age = age(state);
         return age == null ? state : skipRepeatedCropStages ? TinkerCropCycle.advance(state, age) : state.cycle(age);
      }
      if (block instanceof AmethystClusterBlock) return amethyst(state);
      if (block instanceof LayeredCauldronBlock) return state.cycle(BlockStateProperties.LEVEL_CAULDRON);
      if (block instanceof AbstractCauldronBlock) return state;
      if (block instanceof BeehiveBlock) return state.cycle(BlockStateProperties.LEVEL_HONEY);
      if (block instanceof EndPortalFrameBlock) return state.cycle(BlockStateProperties.EYE);
      if (block instanceof RespawnAnchorBlock) return state.cycle(BlockStateProperties.RESPAWN_ANCHOR_CHARGES);
      if (block instanceof RedstoneLampBlock || block instanceof RedStoneOreBlock || block instanceof CopperBulbBlock) {
         return state.cycle(BlockStateProperties.LIT);
      }
      if (block instanceof LightBlock) return state.cycle(BlockStateProperties.LEVEL);
      if (block instanceof LanternBlock) return shift
         ? state.setValue(BlockStateProperties.HANGING, TinkerSurfaceHit.local(hit).y >= 0.5) : state;
      if (block instanceof GrindstoneBlock) return shift ? grindstone(state, hit) : null;
      if (block instanceof BellBlock) return shift ? bell(state, hit) : null;
      if (block instanceof MultifaceBlock || block instanceof VineBlock) return TinkerAttachment.apply(state, hit);
      if (state.is(Blocks.BAMBOO_BLOCK) || state.is(Blocks.STRIPPED_BAMBOO_BLOCK)) {
         if (hit.getDirection().getAxis() == state.getValue(BlockStateProperties.AXIS)) return state;
         return (state.is(Blocks.BAMBOO_BLOCK) ? Blocks.STRIPPED_BAMBOO_BLOCK : Blocks.BAMBOO_BLOCK).withPropertiesOf(state);
      }
      return null;
   }

   static BlockState adjust(BlockState state, IntegerProperty property, Direction side) {
      int amount = side == Direction.UP ? 1 : side.getAxis().isHorizontal() ? -1 : 0;
      int low = property.getPossibleValues().stream().mapToInt(Integer::intValue).min().orElseThrow();
      int high = property.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElseThrow();
      return state.setValue(property, Math.clamp(state.getValue(property) + amount, low, high));
   }

   private static IntegerProperty age(BlockState state) {
      return state.getProperties().stream().filter(property -> property.getName().equals("age"))
         .filter(IntegerProperty.class::isInstance).map(IntegerProperty.class::cast).findFirst().orElse(null);
   }

   private static BlockState candle(BlockState state) {
      boolean wet = state.getValue(BlockStateProperties.WATERLOGGED);
      boolean lit = state.getValue(BlockStateProperties.LIT);
      if (!wet && !lit) return state.setValue(BlockStateProperties.LIT, true);
      return state.cycle(BlockStateProperties.CANDLES).setValue(BlockStateProperties.LIT, false);
   }

   private static BlockState light(BlockState state) {
      boolean wet = state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
      return state.setValue(BlockStateProperties.LIT, !wet && !state.getValue(BlockStateProperties.LIT));
   }

   private static BlockState orderedThickness(BlockState state) {
      var values = List.of(DripstoneThickness.TIP, DripstoneThickness.TIP_MERGE, DripstoneThickness.FRUSTUM,
         DripstoneThickness.MIDDLE, DripstoneThickness.BASE);
      int index = values.indexOf(state.getValue(BlockStateProperties.DRIPSTONE_THICKNESS));
      return state.setValue(BlockStateProperties.DRIPSTONE_THICKNESS, values.get((index + 1) % values.size()));
   }

   private static BlockState amethyst(BlockState state) {
      var blocks = List.of(Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER);
      int index = blocks.indexOf(state.getBlock());
      return index < 0 ? state : blocks.get((index + 1) % blocks.size()).withPropertiesOf(state);
   }

   private static BlockState grindstone(BlockState state, BlockHitResult hit) {
      Direction side = TinkerSurfaceHit.nearestSide(hit);
      if (side.getAxis().isHorizontal() && side != state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()) return state;
      AttachFace attachment = side == Direction.UP ? AttachFace.CEILING : side == Direction.DOWN ? AttachFace.FLOOR : AttachFace.WALL;
      return state.setValue(BlockStateProperties.ATTACH_FACE, attachment);
   }

   private static BlockState bell(BlockState state, BlockHitResult hit) {
      Direction side = TinkerSurfaceHit.nearestSide(hit);
      Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
      if (side.getAxis().isHorizontal() && side.getAxis() != facing.getAxis()) return state;
      BellAttachType attachment = side == Direction.UP ? BellAttachType.CEILING
         : side == Direction.DOWN ? BellAttachType.FLOOR : BellAttachType.SINGLE_WALL;
      if (side == facing.getOpposite() && state.getValue(BlockStateProperties.BELL_ATTACHMENT) != BellAttachType.DOUBLE_WALL) {
         attachment = BellAttachType.DOUBLE_WALL;
      }
      return state.setValue(BlockStateProperties.BELL_ATTACHMENT, attachment);
   }
}
