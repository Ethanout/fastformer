package io.github.fastformer.client.interaction.binding.data;

import com.google.gson.JsonElement;
import io.github.fastformer.client.interaction.binding.input.InputPattern;
import io.github.fastformer.common.interaction.binding.ActionSpec;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;

/** A flattened rule; higher priority and more specific contexts are considered first. */
public record InteractionBinding(ResourceLocation id, Map<String, JsonElement> context, InputPattern input,
   ActionSpec action, int priority, boolean enabled, Optional<ResourceLocation> option) {
   public static final Comparator<InteractionBinding> ORDER = Comparator
      .comparingInt(InteractionBinding::priority).reversed()
      .thenComparing(Comparator.comparingInt((InteractionBinding binding) -> binding.context.size()).reversed());

   public InteractionBinding {
      context = copy(context);
      java.util.Objects.requireNonNull(id);
      java.util.Objects.requireNonNull(input);
      java.util.Objects.requireNonNull(action);
      java.util.Objects.requireNonNull(option);
   }

   @Override public Map<String, JsonElement> context() { return copy(context); }

   private static Map<String, JsonElement> copy(Map<String, JsonElement> source) {
      return source.entrySet().stream().collect(Collectors.toUnmodifiableMap(
         Map.Entry::getKey, entry -> entry.getValue().deepCopy()));
   }
}
