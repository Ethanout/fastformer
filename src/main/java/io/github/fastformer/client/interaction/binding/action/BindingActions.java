package io.github.fastformer.client.interaction.binding.action;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.fastformer.common.interaction.binding.ActionSpec;
import io.github.fastformer.core.registry.Registry;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/** Action factories decode their own arguments before an input can invoke them. */
public final class BindingActions {
   private final Registry<ResourceLocation, Factory> actions = Registry.create("binding actions");
   @FunctionalInterface public interface Factory { DataResult<BindingAction> create(ActionSpec spec); }

   public <T> BindingActions register(ResourceLocation id, Codec<T> arguments, Function<T, BindingAction> action) {
      actions.register(id, spec -> arguments.parse(JsonOps.INSTANCE, spec.arguments()).map(action));
      return this;
   }

   public DataResult<BindingAction> resolve(ActionSpec spec) {
      return actions.get(spec.id()).map(factory -> factory.create(spec))
         .orElseGet(() -> DataResult.error(() -> "Unknown binding action: " + spec.id()));
   }

   public BindingActions freeze() { actions.freeze(); return this; }
}
