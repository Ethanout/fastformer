package io.github.fastformer.client.mixin;

import io.github.fastformer.client.interaction.ClientReachGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
   @Inject(method = "pick(F)V", at = @At("TAIL"))
   private void fastformer$updateReachTransition(float partialTick, CallbackInfo callback) {
      ClientReachGate.update(Minecraft.getInstance());
   }
}
