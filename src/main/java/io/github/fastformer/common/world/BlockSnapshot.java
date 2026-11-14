package io.github.fastformer.common.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable block contents shared by world operations, previews and persistence. */
public record BlockSnapshot(BlockState state, CompoundTag blockEntity) {
   public BlockSnapshot {
      if (state == null) {
         throw new IllegalArgumentException("Block state is required");
      }
      blockEntity = blockEntity == null ? null : blockEntity.copy();
   }

   @Override
   public CompoundTag blockEntity() {
      return this.blockEntity == null ? null : this.blockEntity.copy();
   }
}
