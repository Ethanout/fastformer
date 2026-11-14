package io.github.fastformer.client.interaction.binding.context.key;

import io.github.fastformer.client.interaction.binding.context.BindingContext;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/** One cancellation phase's routing, lifecycle, and physical keyboard facts. */
public record CancellationKeyContext(String session, String keyRoute, boolean cancellable, boolean quitAvailable,
   boolean bindingActive, boolean submissionPending, boolean operationRestore, boolean previewRestore, boolean captured,
   boolean screenClosed, boolean playerPresent, boolean connected, int action,
   boolean controlDown, boolean altDown, boolean shiftDown) {
   public BindingContext freeze(String phase) {
      Map<String, JsonElement> fields = KeyboardFacts.capture(session, phase, screenClosed, playerPresent, action,
         controlDown, altDown, shiftDown);
      fields.put("key_route", new JsonPrimitive(keyRoute));
      fields.put("cancellable", new JsonPrimitive(cancellable));
      fields.put("quit_available", new JsonPrimitive(quitAvailable));
      fields.put("binding_active", new JsonPrimitive(bindingActive));
      fields.put("workspace_submission_pending", new JsonPrimitive(submissionPending));
      fields.put("captured_cancel", new JsonPrimitive(captured));
      fields.put("connected", new JsonPrimitive(connected));
      fields.put("operation_restore_pending", new JsonPrimitive(operationRestore));
      fields.put("preview_restore_pending", new JsonPrimitive(previewRestore));
      return new BindingContext(fields);
   }
}
