package io.github.fastformer.fastplace.placement.mixin;

import io.github.fastformer.fastplace.interaction.ProtectedInteractionTick;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SavedTick.class)
public abstract class InteractionSavedTickMixin<T> implements ProtectedInteractionTick {
   @Unique private boolean fastformer$suppressNeighbors;
   @Override public boolean fastformer$suppressesNeighbors() { return fastformer$suppressNeighbors; }
   @Override public void fastformer$suppressNeighbors(boolean suppress) { fastformer$suppressNeighbors = suppress; }

   @Inject(method = "loadTick", at = @At("RETURN"))
   private static <T> void fastformer$loadPolicy(CompoundTag tag, Function<String, Optional<T>> parser,
         CallbackInfoReturnable<Optional<SavedTick<T>>> callback) {
      if (tag.getBoolean(ProtectedInteractionTick.NBT_KEY)) callback.getReturnValue().ifPresent(
         tick -> ((ProtectedInteractionTick)(Object)tick).fastformer$suppressNeighbors(true));
   }

   @Inject(method = "saveTick(Lnet/minecraft/world/ticks/ScheduledTick;Ljava/util/function/Function;J)Lnet/minecraft/nbt/CompoundTag;",
      at = @At("RETURN"))
   private static <T> void fastformer$saveScheduledPolicy(ScheduledTick<T> tick, Function<T, String> idGetter,
         long gameTime, CallbackInfoReturnable<CompoundTag> callback) {
      if (((ProtectedInteractionTick)(Object)tick).fastformer$suppressesNeighbors()) {
         callback.getReturnValue().putBoolean(ProtectedInteractionTick.NBT_KEY, true);
      }
   }

   @Inject(method = "save", at = @At("RETURN"))
   private void fastformer$savePendingPolicy(Function<T, String> idGetter, CallbackInfoReturnable<CompoundTag> callback) {
      if (fastformer$suppressNeighbors) callback.getReturnValue().putBoolean(ProtectedInteractionTick.NBT_KEY, true);
   }

   @Inject(method = "unpack", at = @At("RETURN"))
   private void fastformer$unpackPolicy(long gameTime, long subTickOrder,
                                      CallbackInfoReturnable<ScheduledTick<T>> callback) {
      if (fastformer$suppressNeighbors) ((ProtectedInteractionTick)(Object)callback.getReturnValue()).fastformer$suppressNeighbors(true);
   }
}
