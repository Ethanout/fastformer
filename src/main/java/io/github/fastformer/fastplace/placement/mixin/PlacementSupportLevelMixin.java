package io.github.fastformer.fastplace.placement.mixin;

import io.github.fastformer.fastplace.placement.context.PlacementSupportQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class PlacementSupportLevelMixin {
   @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
   private void fastformer$placementSupportState(BlockPos pos, CallbackInfoReturnable<BlockState> callback) {
      BlockState state = PlacementSupportQuery.blockState((Level)(Object)this, pos);
      if (state != null) callback.setReturnValue(state);
   }

   @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
   private void fastformer$placementSupportFluid(BlockPos pos, CallbackInfoReturnable<FluidState> callback) {
      BlockState state = PlacementSupportQuery.blockState((Level)(Object)this, pos);
      if (state != null) callback.setReturnValue(state.getFluidState());
   }

   @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
   private void fastformer$placementSupportEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> callback) {
      if (PlacementSupportQuery.blockState((Level)(Object)this, pos) != null) callback.setReturnValue(null);
   }
}
