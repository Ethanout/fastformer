package io.github.fastformer.client.operation.model;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/** Block contents stored by the client workspace and persistent clipboard. */
public record ClientBlockSnapshot(BlockState state, CompoundTag blockEntity) {
   public ClientBlockSnapshot {
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
