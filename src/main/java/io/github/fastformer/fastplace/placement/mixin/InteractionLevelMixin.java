package io.github.fastformer.fastplace.placement.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.fastformer.fastplace.interaction.InteractionUpdateScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class InteractionLevelMixin {
   @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
   private boolean fastformer$writeWithoutNeighbors(BlockPos pos, BlockState state, int flags, int depth,
                                                    Operation<Boolean> original) {
      Level level = (Level)(Object)this;
      if (!InteractionUpdateScope.suppresses(level)) return original.call(pos, state, flags, depth);
      boolean changed = original.call(pos, state, (flags & ~1) | 2 | 16, depth);
      // Door halves form one interactive object. Sync only OPEN, preserving all other custom state.
      if (changed && state.getBlock() instanceof DoorBlock) {
         BlockPos otherPos = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
         BlockState other = level.getBlockState(otherPos);
         if (other.is(state.getBlock()) && other.getValue(DoorBlock.HALF) != state.getValue(DoorBlock.HALF)
               && other.getValue(DoorBlock.OPEN) != state.getValue(DoorBlock.OPEN)) {
            level.setBlock(otherPos, other.setValue(DoorBlock.OPEN, state.getValue(DoorBlock.OPEN)), 2 | 16);
         }
      }
      return changed;
   }

   @Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"), cancellable = true)
   private void fastformer$skipAnalogUpdates(CallbackInfo callback) {
      if (InteractionUpdateScope.suppresses((Level)(Object)this)) callback.cancel();
   }
}
