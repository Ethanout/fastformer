package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Frozen geometry pointer capabilities; target actions remain in the owning snapshot. */
public record GeometryBindingContext(String mode, EnumSet<Fact> facts) {
   public enum Fact {
      VANILLA_POINTER, POINTER_CAPTURED, GIZMO_PHYSICAL, GIZMO_OWNED, GIZMO_CAPTURED, GESTURE_BUSY,
      GIZMO_READY, INTERACTION_CHANNEL, UNDO_CHANNEL, POINT_CHANNEL, LEFT_TARGET, RIGHT_TARGET,
      POINT_SELECTED, DRAFT_PRESENT, PATH_AVAILABLE, FALLBACK_UNDO_CHANNEL, FALLBACK_UNDO_CAPTURED
   }

   public GeometryBindingContext { facts = facts.clone(); }
   @Override public EnumSet<Fact> facts() { return facts.clone(); }

   public BindingContext freeze() {
      Map<String, JsonElement> fields = new HashMap<>();
      fields.put("session", new JsonPrimitive("fastformer:geometry"));
      fields.put("mode", new JsonPrimitive(mode));
      fields.put("input_phase", new JsonPrimitive("geometry_pointer"));
      for (Fact fact : Fact.values()) fields.put("geometry_" + fact.name().toLowerCase(Locale.ROOT), new JsonPrimitive(facts.contains(fact)));
      return new BindingContext(fields);
   }
}
