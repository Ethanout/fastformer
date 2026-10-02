package io.github.fastformer.fastplace.placement.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.fastformer.fastplace.world.WorkspaceTickBarrier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
public abstract class WorkspaceRandomTickMixin {
   @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE", target =
      "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
   private void fastformer$deferRandomBlock(BlockState state, ServerLevel level, BlockPos pos,
                                          RandomSource random, Operation<Void> original) {
      if (!WorkspaceTickBarrier.pausesRandomTick(level, pos)) original.call(state, level, pos, random);
   }

   @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE", target =
      "Lnet/minecraft/world/level/material/FluidState;randomTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
   private void fastformer$deferRandomFluid(FluidState state, net.minecraft.world.level.Level level, BlockPos pos,
                                          RandomSource random, Operation<Void> original) {
      if (!(level instanceof ServerLevel server) || !WorkspaceTickBarrier.pausesRandomTick(server, pos)) original.call(state, level, pos, random);
   }
}
