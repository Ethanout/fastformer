package io.github.fastformer.client.mixin;

import io.github.fastformer.client.input.FastPlaceClientInput;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class IdlePlacementPredictionMixin {
   @Inject(method = "place", at = @At("RETURN"))
   private void fastformer$capturePlacedShape(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> callback) {
      if (context.getLevel().isClientSide() && callback.getReturnValue().consumesAction())
         FastPlaceClientInput.capturePlacedInteraction(context.getClickedPos());
   }
}
