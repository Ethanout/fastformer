package io.github.fastformer.common.mixin.world;

import io.github.fastformer.common.placement.InteractionUpdateScope;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CollectingNeighborUpdater.class)
public abstract class InteractionNeighborUpdaterMixin {
   @Shadow @Final private Level level;

   @Inject(method = {"shapeUpdate", "neighborChanged", "updateNeighborsAtExceptFromFacing"},
      at = @At("HEAD"), cancellable = true)
   private void fastformer$skipInteractionUpdates(CallbackInfo callback) {
      if (InteractionUpdateScope.suppresses(level)) callback.cancel();
   }
}
