package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/** Facts for tool keys; default policy lives entirely in resource binding data. */
public record ToolKeyContext(String session, boolean screenClosed, boolean playerPresent,
   boolean idleActionsAllowed, boolean fillReady, boolean canStartFill, int action,
   boolean controlDown, boolean altDown, boolean shiftDown) {

   public BindingContext freeze() {
      return freeze("dispatch");
   }

   public BindingContext freeze(String phase) {
      Map<String, JsonElement> facts = KeyboardFacts.capture(session, phase, screenClosed, playerPresent, action,
         controlDown, altDown, shiftDown);
      facts.put("idle_actions_allowed", new JsonPrimitive(idleActionsAllowed));
      facts.put("fill_ready", new JsonPrimitive(fillReady));
      facts.put("can_start_fill", new JsonPrimitive(canStartFill));
      return new BindingContext(facts);
   }
}
