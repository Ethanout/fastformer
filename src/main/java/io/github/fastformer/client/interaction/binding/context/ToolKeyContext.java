package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;

/** Facts for tool keys; default policy lives entirely in resource binding data. */
public record ToolKeyContext(String session, boolean screenClosed, boolean playerPresent,
   boolean idleActionsAllowed, boolean fillReady, boolean canStartFill, int action,
   boolean controlDown, boolean altDown, boolean shiftDown) {

   public BindingContext freeze() {
      return freeze("dispatch");
   }

   public BindingContext freeze(String phase) {
      Map<String, JsonElement> facts = new LinkedHashMap<>();
      facts.put("input_phase", new JsonPrimitive(phase));
      facts.put("session", new JsonPrimitive(session));
      facts.put("screen", new JsonPrimitive(!screenClosed));
      facts.put("player_present", new JsonPrimitive(playerPresent));
      facts.put("idle_actions_allowed", new JsonPrimitive(idleActionsAllowed));
      facts.put("fill_ready", new JsonPrimitive(fillReady));
      facts.put("can_start_fill", new JsonPrimitive(canStartFill));
      facts.put("key_action", new JsonPrimitive(switch (action) {
         case 0 -> "release";
         case 1 -> "press";
         case 2 -> "repeat";
         default -> "unknown";
      }));
      JsonArray modifiers = new JsonArray();
      if (controlDown) modifiers.add("control");
      if (altDown) modifiers.add("alt");
      if (shiftDown) modifiers.add("shift");
      facts.put("modifiers", modifiers);
      return new BindingContext(facts);
   }
}
