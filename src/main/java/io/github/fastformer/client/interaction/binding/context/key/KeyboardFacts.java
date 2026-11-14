package io.github.fastformer.client.interaction.binding.context.key;

import io.github.fastformer.client.interaction.binding.context.BindingContext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared physical keyboard facts; callers add only their own domain facts. */
final class KeyboardFacts {
   private KeyboardFacts() {}

   static Map<String, JsonElement> capture(String session, String phase, boolean screenClosed, boolean playerPresent,
      int action, boolean controlDown, boolean altDown, boolean shiftDown) {
      Map<String, JsonElement> fields = new LinkedHashMap<>();
      fields.put("input_phase", new JsonPrimitive(phase));
      fields.put("session", new JsonPrimitive(session));
      fields.put("screen", new JsonPrimitive(!screenClosed));
      fields.put("player_present", new JsonPrimitive(playerPresent));
      fields.put("key_action", new JsonPrimitive(switch (action) {
         case 0 -> "release";
         case 1 -> "press";
         case 2 -> "repeat";
         default -> "unknown";
      }));
      JsonArray modifiers = new JsonArray();
      if (controlDown) modifiers.add("control");
      if (altDown) modifiers.add("alt");
      if (shiftDown) modifiers.add("shift");
      fields.put("modifiers", modifiers);
      return fields;
   }
}
