package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import java.util.Map;

/** AND matching with explicit field negation, shared by flat and tree bindings. */
public final class ContextMatcher {
   private final ContextFields fields;
   public ContextMatcher(ContextFields fields) { this.fields = java.util.Objects.requireNonNull(fields); }

   public boolean matches(Map<String, JsonElement> conditions, BindingContext context) {
      return matches(conditions, context, false);
   }

   /** Missing facts remain undecided while known facts may rule out expensive context capture. */
   public boolean mayMatch(Map<String, JsonElement> conditions, BindingContext knownContext) {
      return matches(conditions, knownContext, true);
   }

   private boolean matches(Map<String, JsonElement> conditions, BindingContext context, boolean allowMissing) {
      for (var condition : conditions.entrySet()) {
         boolean negated = condition.getKey().startsWith("!");
         String name = negated ? condition.getKey().substring(1) : condition.getKey();
         if (!fields.known(name)) return false;
         var actual = context.field(name);
         if (allowMissing && actual.isEmpty()) continue;
         boolean matched = fields.matches(name, condition.getValue(), actual.orElse(null));
         if (matched == negated) return false;
      }
      return true;
   }

   public boolean known(String name) { return fields.known(name.startsWith("!") ? name.substring(1) : name); }
}
