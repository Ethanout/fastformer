package io.github.fastformer.fastplace.placement.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.fastformer.fastplace.interaction.InteractionUpdateScope;
import io.github.fastformer.fastplace.interaction.ProtectedInteractionTick;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelTicks.class)
public abstract class InteractionScheduledTicksMixin<T> {
   @WrapOperation(method = "schedule", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/ticks/LevelChunkTicks;schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V"))
   private void fastformer$rememberPolicy(LevelChunkTicks<T> container, ScheduledTick<T> tick, Operation<Void> original) {
      if (InteractionUpdateScope.protectsScheduledTicks()) {
         ((ProtectedInteractionTick)(Object)tick).fastformer$suppressNeighbors(true);
      }
      original.call(container, tick);
   }

   @WrapOperation(method = "runCollectedTicks", at = @At(value = "INVOKE",
      target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
   private void fastformer$runWithPolicy(BiConsumer<BlockPos, T> ticker, Object pos, Object type,
                                        Operation<Void> original, @Local ScheduledTick<T> tick) {
      if (io.github.fastformer.fastplace.world.WorkspaceTickBarrier.defer((LevelTicks<T>)(Object)this, tick)) return;
      if (((ProtectedInteractionTick)(Object)tick).fastformer$suppressesNeighbors()) {
         InteractionUpdateScope.runScheduled(() -> { original.call(ticker, pos, type); return null; });
      } else original.call(ticker, pos, type);
   }
}
