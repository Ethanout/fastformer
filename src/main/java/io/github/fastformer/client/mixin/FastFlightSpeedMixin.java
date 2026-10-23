package io.github.fastformer.client.mixin;

import io.github.fastformer.client.input.FastFlightController;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class FastFlightSpeedMixin {
   @Inject(method = "getFlyingSpeed", at = @At("RETURN"), cancellable = true)
   private void fastformer$boostLocalFlight(CallbackInfoReturnable<Float> callback) {
      if ((Object)this instanceof LocalPlayer player) {
         callback.setReturnValue(FastFlightController.flyingSpeed(player, callback.getReturnValueF()));
      }
   }
}
