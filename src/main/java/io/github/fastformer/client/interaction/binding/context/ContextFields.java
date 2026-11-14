package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import io.github.fastformer.core.registry.Registry;
import java.util.List;
import java.util.function.BiPredicate;

/** Extensible field semantics; unknown fields never match by accident. */
public final class ContextFields {
   private final Registry<String, BiPredicate<JsonElement, JsonElement>> fields = Registry.create("binding context");

   public ContextFields register(String name, BiPredicate<JsonElement, JsonElement> matcher) {
      if (name.isBlank() || name.startsWith("!")) throw new IllegalArgumentException("Invalid context field");
      fields.register(name, matcher);
      return this;
   }

   public boolean known(String name) { return fields.get(name).isPresent(); }

   public boolean matches(String name, JsonElement expected, JsonElement actual) {
      return fields.get(name).map(matcher -> actual != null && matcher.test(expected, actual)).orElse(false);
   }

   public ContextFields freeze() { fields.freeze(); return this; }

   public static ContextFields defaults() {
      ContextFields result = new ContextFields();
      for (String field : List.of("session", "stage", "mode", "profile", "sneaking", "screen",
         "aiming", "tinker_consumes", "range", "in_fluid", "through_fluids", "near_first_point",
         "key_action", "input_phase", "player_present", "idle_actions_allowed", "fill_ready", "can_start_fill",
         "can_replace", "creative", "feature_enabled", "window_active", "interaction_allowed", "use_held", "standard_mouse_button",
         "key_route", "paste_allowed", "workspace_active", "workspace_editing", "workspace_submission_pending",
         "vanilla_pointer", "candidate_overlaps", "pointer_channel", "middle_enabled", "pointer_captured",
         "undo_captured", "scroll_context", "modifier_yields")) {
         result.register(field, JsonElement::equals);
      }
      for (String field : List.of("items", "aiming_tag", "modifiers")) result.register(field, ContextFields::contains);
      return result;
   }

   private static boolean contains(JsonElement expected, JsonElement actual) {
      if (expected.isJsonArray()) {
         for (JsonElement element : expected.getAsJsonArray()) if (!contains(element, actual)) return false;
         return true;
      }
      if (!actual.isJsonArray()) return expected.equals(actual);
      for (JsonElement value : actual.getAsJsonArray()) if (value.equals(expected)) return true;
      return false;
   }
}
