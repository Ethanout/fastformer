package io.github.fastformer.client.render;

import io.github.fastformer.client.render.mask.SourceMaskRenderFilter;
import io.github.fastformer.fastplace.geometry.BlockPositionMaps;
import io.github.fastformer.fastplace.geometry.BlockPositionSets;
import java.util.Set;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Uses vanilla face occlusion across the unified preview volume. */
public final class PreviewBlockOcclusion {
   private PreviewBlockOcclusion() {
   }

   public static BlockGetter level(Set<BlockPos> blocks, BlockState state) {
      return level(blocks, state, Map.of());
   }

   public static BlockAndTintGetter level(BlockAndTintGetter base, Map<BlockPos, BlockState> states) {
      return new PreviewLevel(base, BlockPositionMaps.copyOf(states), Blocks.AIR.defaultBlockState());
   }

   public static BlockGetter level(
      Set<BlockPos> blocks, BlockState defaultState, Map<BlockPos, BlockState> stateOverrides
   ) {
      return new PreviewLevel(BlockPositionSets.copyOf(blocks), defaultState, BlockPositionMaps.copyOf(stateOverrides));
   }

   public static boolean shouldRender(
      BlockGetter previewLevel,
      Set<BlockPos> blocks,
      BlockState state,
      BlockPos pos,
      Direction direction
   ) {
      BlockPos neighbor = pos.relative(direction);
      return !hasPreviewNeighbor(blocks, pos, direction)
         || Block.shouldRenderFace(state, previewLevel, pos, direction, neighbor);
   }

   public static boolean hasPreviewNeighbor(Set<BlockPos> blocks, BlockPos pos, Direction direction) {
      return blocks.contains(pos.relative(direction));
   }

   private static final class PreviewLevel implements BlockAndTintGetter {
      private final Set<BlockPos> blocks;
      private final Map<BlockPos, BlockState> states;
      private final BlockState defaultState;
      private final Map<BlockPos, BlockState> stateOverrides;
      private final BlockAndTintGetter base;

      PreviewLevel(Set<BlockPos> blocks, BlockState defaultState, Map<BlockPos, BlockState> stateOverrides) {
         this.base = null;
         this.blocks = blocks;
         this.states = Map.of();
         this.defaultState = defaultState;
         this.stateOverrides = stateOverrides;
      }

      PreviewLevel(BlockAndTintGetter base, Map<BlockPos, BlockState> states, BlockState defaultState) {
         this.base = base;
         this.blocks = states.keySet();
         this.states = states;
         this.defaultState = defaultState;
         this.stateOverrides = Map.of();
      }

      @Override
      public BlockEntity getBlockEntity(BlockPos pos) {
         return null;
      }

      @Override
      public BlockState getBlockState(BlockPos pos) {
         if (this.blocks.contains(pos)) {
            return this.states.getOrDefault(pos, this.stateOverrides.getOrDefault(pos, this.defaultState));
         }
         if (this.base == null) {
            return Blocks.AIR.defaultBlockState();
         }
         // The mask is render only, so the client level still holds the masked source block.
         // Present that position as hidden here as well, or the geometry builder culls the
         // faces between a ghost block and the masked source block. Only this base fallback is
         // masked. The preview blocks above keep their own state.
         if (SourceMaskRenderFilter.instance().hides(pos)) {
            return SourceMaskRenderFilter.maskedBlockState();
         }
         return this.base.getBlockState(pos);
      }

      @Override
      public FluidState getFluidState(BlockPos pos) {
         return this.getBlockState(pos).getFluidState();
      }

      @Override
      public int getHeight() {
         return 384;
      }

      @Override
      public int getMinBuildHeight() {
         return -64;
      }

      @Override
      public float getShade(net.minecraft.core.Direction direction, boolean shade) {
         return base == null ? 1.0F : base.getShade(direction, shade);
      }

      @Override
      public LevelLightEngine getLightEngine() {
         return base == null ? null : base.getLightEngine();
      }

      @Override
      public int getBlockTint(BlockPos pos, ColorResolver resolver) {
         return base == null ? -1 : base.getBlockTint(pos, resolver);
      }
   }
}
