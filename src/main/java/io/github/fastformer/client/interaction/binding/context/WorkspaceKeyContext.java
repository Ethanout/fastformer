package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/** Frozen workspace facts shared by movement-key ownership probing and command dispatch. */
public record WorkspaceKeyContext(String session, String keyRoute, boolean pasteAllowed, boolean workspaceActive,
   boolean editing, boolean submissionPending, boolean screenClosed, boolean playerPresent, int action,
   boolean controlDown, boolean altDown, boolean shiftDown, int undoDepth) {
   public WorkspaceKeyContext {
      if (undoDepth < 0) throw new IllegalArgumentException("An undo depth cannot be negative");
   }

   public BindingContext freeze() {
      Map<String, JsonElement> fields = KeyboardFacts.capture(session, "workspace", screenClosed, playerPresent, action,
         controlDown, altDown, shiftDown);
      fields.put("key_route", new JsonPrimitive(keyRoute));
      fields.put("paste_allowed", new JsonPrimitive(pasteAllowed));
      fields.put("workspace_active", new JsonPrimitive(workspaceActive));
      fields.put("workspace_editing", new JsonPrimitive(editing));
      fields.put("workspace_submission_pending", new JsonPrimitive(submissionPending));
      fields.put("workspace_undo_available", new JsonPrimitive(undoDepth > 0));
      return new BindingContext(fields);
   }
}
