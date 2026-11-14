package io.github.fastformer.client.interaction.binding.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.common.quickshape.mode.QuickShapeStage;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** One quick-shape event's frozen pointing, capture and scroll availability. */
public record QuickShapeBindingContext(QuickShapeStage stage, ResourceLocation mode, ResourceLocation profile, Set<Fact> facts) {
   public QuickShapeBindingContext {
      Objects.requireNonNull(stage, "stage");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(profile, "profile");
      facts = Set.copyOf(facts);
   }

   public enum Fact {
      VANILLA_POINTER, CANDIDATE_OVERLAPS, POINTER_CHANNEL, MIDDLE_ENABLED, MIDDLE_IGNORED,
      POINTER_CAPTURED, UNDO_CAPTURED, SCROLL_CONTEXT, MODIFIER_YIELDS;
      String field() { return name().toLowerCase(Locale.ROOT); }
   }

   public boolean vanillaPointer() { return facts.contains(Fact.VANILLA_POINTER); }
   public boolean overlaps() { return facts.contains(Fact.CANDIDATE_OVERLAPS); }
   public boolean pointerChannel() { return facts.contains(Fact.POINTER_CHANNEL); }
   public boolean middleEnabled() { return facts.contains(Fact.MIDDLE_ENABLED); }
   public boolean middleIgnored() { return facts.contains(Fact.MIDDLE_IGNORED); }
   public boolean pointerCaptured() { return facts.contains(Fact.POINTER_CAPTURED); }
   public boolean undoCaptured() { return facts.contains(Fact.UNDO_CAPTURED); }
   public boolean scrollContext() { return facts.contains(Fact.SCROLL_CONTEXT); }
   public boolean modifierYields() { return facts.contains(Fact.MODIFIER_YIELDS); }

   public BindingContext freeze() {
      Map<String, JsonElement> fields = new LinkedHashMap<>();
      fields.put("session", new JsonPrimitive(BuiltinModes.QUICK_SHAPE.value().toString()));
      fields.put("stage", new JsonPrimitive(stage.name().toLowerCase(Locale.ROOT)));
      fields.put("mode", new JsonPrimitive(mode.toString()));
      fields.put("profile", new JsonPrimitive(profile.toString()));
      fields.put("input_phase", new JsonPrimitive("quick_shape_pointer"));
      for (Fact fact : Fact.values()) fields.put(fact.field(), new JsonPrimitive(facts.contains(fact)));
      return new BindingContext(fields);
   }
}
