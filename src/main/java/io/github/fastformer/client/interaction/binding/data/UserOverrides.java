package io.github.fastformer.client.interaction.binding.data;

import com.google.gson.*;
import java.util.*;

/** An override replaces only supplied fields; omitted actions and contexts retain their defaults. */
public final class UserOverrides {
   private UserOverrides() {}

   public static BindingFile merge(BindingFile defaults, JsonObject overrides) {
      Map<String, JsonObject> rules = new LinkedHashMap<>();
      for (JsonElement value : defaults.encode().getAsJsonArray("bindings")) {
         JsonObject rule = value.getAsJsonObject();
         rules.put(rule.get("id").getAsString(), rule);
      }
      List<String> diagnostics = new ArrayList<>(defaults.diagnostics());
      if (overrides.has("bindings")) {
         for (JsonElement value : overrides.getAsJsonArray("bindings")) {
            try {
               JsonObject patch = value.getAsJsonObject();
               String id = patch.get("id").getAsString();
               JsonObject merged = rules.containsKey(id) ? rules.get(id).deepCopy() : new JsonObject();
               patch.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue().deepCopy()));
               rules.put(id, merged);
            } catch (RuntimeException failure) { diagnostics.add("Invalid user override: " + failure.getMessage()); }
         }
      }
      JsonObject source = defaults.encode();
      JsonArray merged = new JsonArray();
      rules.values().forEach(merged::add);
      source.add("bindings", merged);
      BindingFile file = BindingFile.parse(source);
      diagnostics.addAll(file.diagnostics());
      return new BindingFile(file.bindings(), file.options(), diagnostics);
   }
}
