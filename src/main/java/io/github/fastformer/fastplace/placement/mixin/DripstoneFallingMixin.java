package io.github.fastformer.fastplace.placement.mixin;

import io.github.fastformer.fastplace.world.BlockActivityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PointedDripstoneBlock.class)
public abstract class DripstoneFallingMixin {
   @Inject(method = "spawnFallingStalactite", at = @At("HEAD"), cancellable = true)
   private static void fastformer$preventFalling(BlockState state, ServerLevel level, BlockPos pos, CallbackInfo callback) {
      if (BlockActivityRules.get(level.getServer()).fallingDisabled()) callback.cancel();
   }
}
