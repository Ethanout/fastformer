package io.github.fastformer.client.interaction.binding.context.key;

import io.github.fastformer.client.interaction.binding.context.BindingContext;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/** Frozen keyboard and prompt-presence facts; response delivery stays in the input adapter. */
public record HistoryKeyContext(String session, boolean screenClosed, boolean playerPresent, boolean pending,
   int action, boolean controlDown, boolean altDown, boolean shiftDown) {
   public BindingContext freeze() {
      Map<String, JsonElement> fields = KeyboardFacts.capture(session, "history_prompt", screenClosed, playerPresent, action,
         controlDown, altDown, shiftDown);
      fields.put("history_prompt", new JsonPrimitive(pending));
      return new BindingContext(fields);
   }
}
