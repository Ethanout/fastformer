package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Frozen idle entry facts, including recognition of the first press's anchor. */
public record IdleBindingContext(EnumSet<Fact> facts) {
   public enum Fact { ALT, CONTROL, DISTANT, ENTITY, ANCHOR, TINKER, PASSTHROUGH, REPLAY, OUTLINE, DIRECT, PLACEABLE }
   public IdleBindingContext { facts = facts.clone(); }
   @Override public EnumSet<Fact> facts() { return facts.clone(); }

   public BindingContext freeze() {
      return freeze(Map.of());
   }

   public BindingContext freeze(Map<String, JsonElement> environment) {
      Map<String, JsonElement> fields = new HashMap<>(environment);
      fields.put("session", new JsonPrimitive("fastformer:idle"));
      fields.put("input_phase", new JsonPrimitive("idle_pointer"));
      for (Fact fact : Fact.values()) fields.put("idle_" + fact.name().toLowerCase(Locale.ROOT), new JsonPrimitive(facts.contains(fact)));
      fields.put("double_second_press", new JsonPrimitive(facts.contains(Fact.ANCHOR)));
      fields.put("range", new JsonPrimitive(facts.contains(Fact.DISTANT) ? "far" : "near"));
      fields.put("aiming", new JsonPrimitive(facts.contains(Fact.OUTLINE) ? "block" : "air"));
      fields.put("screen", new JsonPrimitive(false));
      fields.put("tinker_consumes", new JsonPrimitive(facts.contains(Fact.TINKER)));
      JsonArray modifiers = new JsonArray();
      if (facts.contains(Fact.ALT)) modifiers.add("alt");
      if (facts.contains(Fact.CONTROL)) modifiers.add("control");
      fields.put("modifiers", modifiers);
      return new BindingContext(fields);
   }
}
