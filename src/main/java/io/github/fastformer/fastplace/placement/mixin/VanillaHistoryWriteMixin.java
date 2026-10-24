package io.github.fastformer.fastplace.placement.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.fastformer.fastplace.world.BlockEditTransaction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Level.class)
public abstract class VanillaHistoryWriteMixin {
   @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
   private boolean fastformer$captureVanillaWrite(BlockPos pos, BlockState state, int flags, int depth,
                                                  Operation<Boolean> original) {
      return BlockEditTransaction.observeWrite((Level)(Object)this, pos, () -> original.call(pos, state, flags, depth));
   }
}
