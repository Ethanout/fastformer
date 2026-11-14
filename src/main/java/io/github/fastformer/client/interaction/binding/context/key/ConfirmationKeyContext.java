package io.github.fastformer.client.interaction.binding.context.key;

import io.github.fastformer.client.interaction.binding.context.BindingContext;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/** Frozen admission facts for reconnect, selection, and placement confirmation. */
public record ConfirmationKeyContext(String session, String keyRoute, boolean submissionAllowed,
   boolean screenClosed, boolean playerPresent, boolean connected, boolean restorePending,
   boolean canConfirm, boolean vanillaPointer, int action, boolean controlDown, boolean altDown, boolean shiftDown) {
   public BindingContext freeze(String phase) {
      Map<String, JsonElement> fields = KeyboardFacts.capture(session, phase, screenClosed, playerPresent, action,
         controlDown, altDown, shiftDown);
      fields.put("key_route", new JsonPrimitive(keyRoute));
      fields.put("submission_allowed", new JsonPrimitive(submissionAllowed));
      fields.put("connected", new JsonPrimitive(connected));
      fields.put("restore_pending", new JsonPrimitive(restorePending));
      fields.put("can_confirm", new JsonPrimitive(canConfirm));
      fields.put("vanilla_pointer", new JsonPrimitive(vanillaPointer));
      return new BindingContext(fields);
   }
}
