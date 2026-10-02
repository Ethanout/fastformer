package io.github.fastformer.fastplace.placement.mixin;

import io.github.fastformer.fastplace.world.BlockActivityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({FallingBlock.class, ScaffoldingBlock.class})
public abstract class FallingBlockMixin {
   @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
   private void fastformer$preventFalling(BlockState state, ServerLevel level, BlockPos pos,
                                         RandomSource random, CallbackInfo callback) {
      if (BlockActivityRules.get(level.getServer()).fallingDisabled()) callback.cancel();
   }
}
