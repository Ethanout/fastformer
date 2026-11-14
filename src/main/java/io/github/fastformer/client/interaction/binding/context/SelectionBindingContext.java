package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Pointer facts frozen before a rule chooses its logical operation. */
public record SelectionBindingContext(String tool, String geometry, EnumSet<Fact> facts) {
   public enum Fact {
      VANILLA_POINTER, POINTER_CAPTURED, GESTURE_BUSY, LOCAL_ACTIVE, DRAFT_ACTIVE, SESSION_ACTIVE,
      CAN_CREATE, REMOTE_POINTING, UNDO_PREVIOUS_POINT, WORLD_SAMPLING, SAMPLING_EDITING,
      GIZMO_TARGET, FACE_TARGET, PART_TARGET, CREATE_TARGET, NO_TARGET, CREATE_ALLOWED, WORLD_HIT, REMOVE_HIT, ADD_HIT,
      REMOTE_CUBOID, REMOTE_PRISM, SELECTION_READY, CONFIRMED, ADJUSTMENT_STARTED,
      ALT, ADJUST, LEFT_CHANNEL, POINT_CHANNEL, POINT_DRAG_CHANNEL, EDGE_CHANNEL,
      REMOTE_GIZMO_TARGET, POINT_TARGET, EDGE_TARGET, CANDIDATE, WORLD_TARGET, REMOTE_FACE_TARGET,
      PRISM_BASE_OPEN, CLICK_CAPTURED, DRAG_CAPTURED, UNDO_CAPTURED, PATH_CHANNEL,
      REMOTE_GIZMO_READY, SELECT_CHANNEL, FACE_DRAG_READY, POINT_DRAG_READY
   }
   public SelectionBindingContext { facts = facts.clone(); }
   @Override public EnumSet<Fact> facts() { return facts.clone(); }
   public BindingContext freeze() {
      Map<String, JsonElement> fields = new HashMap<>();
      fields.put("session", new JsonPrimitive("fastformer:selection"));
      fields.put("input_phase", new JsonPrimitive("selection_pointer"));
      fields.put("selection_tool", new JsonPrimitive(tool));
      fields.put("mode", new JsonPrimitive(geometry));
      for (Fact fact : Fact.values()) fields.put("selection_" + fact.name().toLowerCase(Locale.ROOT), new JsonPrimitive(facts.contains(fact)));
      return new BindingContext(fields);
   }
}
