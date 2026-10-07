package io.github.fastformer.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.fastformer.fastplace.interaction.InteractionUpdateScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MultiPlayerGameMode.class)
public abstract class BlockBreakPredictionMixin {
   @Shadow @Final private Minecraft minecraft;

   @WrapMethod(method = "destroyBlock")
   private boolean fastformer$predictWithUpdatePolicy(BlockPos pos, Operation<Boolean> original) {
      if (minecraft.level == null) return original.call(pos);
      return InteractionUpdateScope.interact(minecraft.level, minecraft.player, () -> original.call(pos));
   }
}
