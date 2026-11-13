package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** One input's frozen facts; matchers and actions observe the same snapshot. */
public final class BindingContext {
   private final Map<String, JsonElement> fields;

   public BindingContext(Map<String, JsonElement> fields) {
      this.fields = fields.entrySet().stream().collect(Collectors.toUnmodifiableMap(
         Map.Entry::getKey, entry -> entry.getValue().deepCopy()));
   }

   public Optional<JsonElement> field(String name) {
      return Optional.ofNullable(fields.get(name)).map(JsonElement::deepCopy);
   }
}
