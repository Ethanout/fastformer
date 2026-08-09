package io.github.fastformer.client;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Uses vanilla face occlusion against the confirmed preview volume only. */
public final class PreviewBlockOcclusion {
   private PreviewBlockOcclusion() {
   }

   public static BlockGetter level(Set<BlockPos> blocks, BlockState state) {
      return new PreviewLevel(Set.copyOf(blocks), state);
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

   static boolean hasPreviewNeighbor(Set<BlockPos> blocks, BlockPos pos, Direction direction) {
      return blocks.contains(pos.relative(direction));
   }

   private static final class PreviewLevel implements BlockGetter {
      private final Set<BlockPos> blocks;
      private final BlockState state;

      PreviewLevel(Set<BlockPos> blocks, BlockState state) {
         this.blocks = blocks;
         this.state = state;
      }

      @Override
      public BlockEntity getBlockEntity(BlockPos pos) {
         return null;
      }

      @Override
      public BlockState getBlockState(BlockPos pos) {
         return this.blocks.contains(pos) ? this.state : Blocks.AIR.defaultBlockState();
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
   }
}
