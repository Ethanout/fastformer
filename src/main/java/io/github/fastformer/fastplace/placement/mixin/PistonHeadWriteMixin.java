package io.github.fastformer.fastplace.placement.mixin;

import io.github.fastformer.fastplace.world.WorldWriteSideEffectGuard;
import io.github.fastformer.fastplace.interaction.InteractionUpdateScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The transaction or snapshot restore owns the base; a head callback must not destroy it. */
@Mixin(PistonHeadBlock.class)
public abstract class PistonHeadWriteMixin {
   @Inject(method = "onRemove", at = @At("HEAD"), cancellable = true)
   private void fastformer$keepTransactionBase(BlockState state, Level level, BlockPos pos,
      BlockState target, boolean moving, CallbackInfo callback) {
      if (WorldWriteSideEffectGuard.writing(level, pos) || InteractionUpdateScope.suppresses(level)) callback.cancel();
   }
}
