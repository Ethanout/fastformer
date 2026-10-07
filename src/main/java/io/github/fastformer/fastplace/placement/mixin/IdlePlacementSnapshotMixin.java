package io.github.fastformer.fastplace.placement.mixin;

import io.github.fastformer.server.input.IdleInteractionSnapshots;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class IdlePlacementSnapshotMixin {
   @Inject(method = "place", at = @At("RETURN"))
   private void fastformer$capturePlacedShape(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> result) {
      if (context.getPlayer() instanceof ServerPlayer player && result.getReturnValue().consumesAction()) {
         IdleInteractionSnapshots.placed(player, context.getClickedPos());
      }
   }
}
