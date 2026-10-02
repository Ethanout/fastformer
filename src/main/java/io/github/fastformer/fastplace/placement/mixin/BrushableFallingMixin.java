package io.github.fastformer.fastplace.placement.mixin;

import io.github.fastformer.fastplace.world.BlockActivityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BrushableBlock.class)
public abstract class BrushableFallingMixin {
   // Keep archaeology progress reset active; stop only the falling part of the tick.
   @Inject(method = "tick", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/block/FallingBlock;isFree(Lnet/minecraft/world/level/block/state/BlockState;)Z"), cancellable = true)
   private void fastformer$preventFalling(BlockState state, ServerLevel level, BlockPos pos,
                                         RandomSource random, CallbackInfo callback) {
      if (BlockActivityRules.get(level.getServer()).fallingDisabled()) callback.cancel();
   }
}
