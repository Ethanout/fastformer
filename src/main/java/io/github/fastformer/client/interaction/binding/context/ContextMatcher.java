package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import java.util.Map;

/** AND matching with explicit field negation, shared by flat and tree bindings. */
public final class ContextMatcher {
   private final ContextFields fields;
   public ContextMatcher(ContextFields fields) { this.fields = java.util.Objects.requireNonNull(fields); }

   public boolean matches(Map<String, JsonElement> conditions, BindingContext context) {
      for (var condition : conditions.entrySet()) {
         boolean negated = condition.getKey().startsWith("!");
         String name = negated ? condition.getKey().substring(1) : condition.getKey();
         if (!fields.known(name)) return false;
         boolean matched = fields.matches(name, condition.getValue(), context.field(name).orElse(null));
         if (matched == negated) return false;
      }
      return true;
   }

   public boolean known(String name) { return fields.known(name.startsWith("!") ? name.substring(1) : name); }
}
