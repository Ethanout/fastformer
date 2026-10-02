package io.github.fastformer.client.mixin;

import io.github.fastformer.client.interaction.ClientBlockTinker;
import io.github.fastformer.client.placement.ClientForcedPlacement;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class BlockUsePredictionMixin {
   // The enclosing prediction still sends the use packet. Only the vanilla local action is skipped.
   @Inject(method = "performUseItemOn", at = @At("HEAD"), cancellable = true)
   private void fastformer$waitForServerBlockUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                       CallbackInfoReturnable<InteractionResult> callback) {
      if (ClientForcedPlacement.consumes(player, hand, hit) || ClientBlockTinker.consumes(player, hand, hit)) {
         callback.setReturnValue(InteractionResult.SUCCESS);
      }
   }
}
