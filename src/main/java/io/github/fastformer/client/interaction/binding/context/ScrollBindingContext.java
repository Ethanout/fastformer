package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonPrimitive;
import java.util.Map;

/** Wheel facts captured before the input owner queues an operation. */
public record ScrollBindingContext(String session, String route, boolean workspaceActive, boolean candidateAvailable,
   boolean modifierYields, boolean vanillaPointer, boolean nonzero) {
   public BindingContext freeze() {
      return new BindingContext(Map.of(
         "session", new JsonPrimitive(session), "input_phase", new JsonPrimitive("wheel"),
         "scroll_route", new JsonPrimitive(route), "workspace_active", new JsonPrimitive(workspaceActive),
         "scroll_context", new JsonPrimitive(candidateAvailable), "modifier_yields", new JsonPrimitive(modifierYields),
         "vanilla_pointer", new JsonPrimitive(vanillaPointer), "scroll_nonzero", new JsonPrimitive(nonzero)));
   }
}
