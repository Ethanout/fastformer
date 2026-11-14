package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;

/** Frozen workspace facts shared by movement-key ownership probing and command dispatch. */
public record WorkspaceKeyContext(String session, String keyRoute, boolean pasteAllowed, boolean workspaceActive,
   boolean editing, boolean submissionPending, boolean screenClosed, boolean playerPresent, int action,
   boolean controlDown, boolean altDown, boolean shiftDown) {
   public BindingContext freeze() {
      Map<String, JsonElement> fields = new LinkedHashMap<>();
      fields.put("input_phase", new JsonPrimitive("workspace"));
      fields.put("session", new JsonPrimitive(session));
      fields.put("key_route", new JsonPrimitive(keyRoute));
      fields.put("paste_allowed", new JsonPrimitive(pasteAllowed));
      fields.put("workspace_active", new JsonPrimitive(workspaceActive));
      fields.put("workspace_editing", new JsonPrimitive(editing));
      fields.put("workspace_submission_pending", new JsonPrimitive(submissionPending));
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
      return new BindingContext(fields);
   }
}
