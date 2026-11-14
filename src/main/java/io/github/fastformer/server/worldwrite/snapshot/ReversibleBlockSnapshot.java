package io.github.fastformer.server.worldwrite.snapshot;

import io.github.fastformer.server.worldwrite.effect.WorldWriteSideEffectGuard;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public record ReversibleBlockSnapshot(
   BlockPos pos,
   BlockState state,
   FluidState fluidState,
   BlockEntitySnapshot blockEntity
) {
   public ReversibleBlockSnapshot {
      pos = pos.immutable();
   }

   public static Optional<ReversibleBlockSnapshot> capture(ServerLevel level, BlockPos pos) {
      try {
         BlockState state = level.getBlockState(pos);
         BlockEntity blockEntity = level.getBlockEntity(pos);
         BlockEntitySnapshot data = blockEntity == null
            ? null
            : new BlockEntitySnapshot(blockEntity.saveWithFullMetadata(level.registryAccess()));
         return Optional.of(new ReversibleBlockSnapshot(pos, state, level.getFluidState(pos), data));
      } catch (RuntimeException exception) {
         return Optional.empty();
      }
   }

   public boolean restore(ServerLevel level, int flags) {
      return this.placeAt(level, this.pos, flags);
   }

   /** Returns whether the live world is exactly represented by this snapshot. */
   public boolean matches(ServerLevel level, BlockPos target) {
      try {
         if (!level.getBlockState(target).equals(this.state)
            || !level.getFluidState(target).equals(this.fluidState)) {
            return false;
         }
         BlockEntity live = level.getBlockEntity(target);
         return this.blockEntity == null
            ? live == null
            : this.blockEntity.matches(live, level, target);
      } catch (RuntimeException exception) {
         return false;
      }
   }

   public boolean sameContents(ReversibleBlockSnapshot other) {
      if (other == null) {
         return false;
      }
      if (!Objects.equals(this.state, other.state) || !Objects.equals(this.fluidState, other.fluidState)) {
         return false;
      }
      if (this.blockEntity == null || other.blockEntity == null) {
         return this.blockEntity == other.blockEntity;
      }
      return this.blockEntity.data().equals(other.blockEntity.data());
   }

   public boolean placeAt(ServerLevel level, BlockPos target, int flags) {
      try {
         BlockState currentState = level.getBlockState(target);
         if (!currentState.equals(this.state) && !WorldWriteSideEffectGuard.setBlock(level, target, this.state, flags)) {
            return false;
         }

         BlockEntity targetEntity = level.getBlockEntity(target);
         if (this.blockEntity == null) {
            // A previous implementation left a newly-created block entity in
            // place when the original block had none.  Explicitly remove it
            // so undo restores the complete block state, not just the block.
            if (targetEntity != null) {
               level.removeBlockEntity(target);
            }
         } else {
            if (!(this.state.getBlock() instanceof EntityBlock entityBlock)) {
               return false;
            }
            // Load off-world first. A crash or malformed NBT before the final
            // insertion leaves the previous live block entity untouched;
            // after insertion the new object already represents the complete
            // target fingerprint rather than an intermediate partial load.
            BlockEntity replacement = entityBlock.newBlockEntity(target, this.state);
            if (replacement == null) {
               return false;
            }
            replacement.loadWithComponents(this.blockEntity.dataAt(target), level.registryAccess());
            level.setBlockEntity(replacement);
            targetEntity = level.getBlockEntity(target);
            if (targetEntity != replacement) {
               return false;
            }
            replacement.setChanged();
            level.sendBlockUpdated(target, this.state, this.state, flags | 2);
         }
         return this.matches(level, target);
      } catch (RuntimeException exception) {
         return false;
      }
   }
}
