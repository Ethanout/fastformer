package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;

/** Frozen tool availability used by mouse, repeat-use and per-tick binding signals. */
public record ReplacementContext(boolean active, boolean canReplace, boolean creative, boolean featureEnabled,
   boolean screenClosed, boolean windowActive, boolean interactionAllowed, boolean useHeld) {
   public BindingContext freeze() {
      Map<String, JsonElement> facts = new LinkedHashMap<>();
      facts.put("session", new JsonPrimitive(active ? "fastformer:quick_replace" : "fastformer:idle"));
      facts.put("can_replace", new JsonPrimitive(canReplace));
      facts.put("creative", new JsonPrimitive(creative));
      facts.put("feature_enabled", new JsonPrimitive(featureEnabled));
      facts.put("screen", new JsonPrimitive(!screenClosed));
      facts.put("window_active", new JsonPrimitive(windowActive));
      facts.put("interaction_allowed", new JsonPrimitive(interactionAllowed));
      facts.put("use_held", new JsonPrimitive(useHeld));
      return new BindingContext(facts);
   }
}
