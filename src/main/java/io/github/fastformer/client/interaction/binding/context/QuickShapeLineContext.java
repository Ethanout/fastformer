package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;

/** One line event's frozen pointing, capture and scroll availability. */
public record QuickShapeLineContext(boolean vanillaPointer, boolean overlaps, boolean pointerChannel,
   boolean middleEnabled, boolean pointerCaptured, boolean undoCaptured, boolean scrollContext, boolean modifierYields) {
   public BindingContext freeze() {
      Map<String, JsonElement> fields = new LinkedHashMap<>();
      fields.put("session", new JsonPrimitive("fastformer:quick_shape"));
      fields.put("stage", new JsonPrimitive("line"));
      fields.put("input_phase", new JsonPrimitive("line_pointer"));
      fields.put("vanilla_pointer", new JsonPrimitive(vanillaPointer));
      fields.put("candidate_overlaps", new JsonPrimitive(overlaps));
      fields.put("pointer_channel", new JsonPrimitive(pointerChannel));
      fields.put("middle_enabled", new JsonPrimitive(middleEnabled));
      fields.put("pointer_captured", new JsonPrimitive(pointerCaptured));
      fields.put("undo_captured", new JsonPrimitive(undoCaptured));
      fields.put("scroll_context", new JsonPrimitive(scrollContext));
      fields.put("modifier_yields", new JsonPrimitive(modifierYields));
      return new BindingContext(fields);
   }
}
