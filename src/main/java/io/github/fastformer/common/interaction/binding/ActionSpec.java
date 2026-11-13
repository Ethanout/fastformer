package io.github.fastformer.common.interaction.binding;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;

/** Registered action ID and codec-validated arguments; JSON ownership never escapes. */
public record ActionSpec(ResourceLocation id, JsonObject arguments) {
   public static final Codec<ActionSpec> CODEC = Codec.PASSTHROUGH.comapFlatMap(dynamic -> {
      try { return DataResult.success(parse(dynamic.convert(JsonOps.INSTANCE).getValue())); }
      catch (IllegalArgumentException failure) { return DataResult.error(failure::getMessage); }
   }, spec -> new Dynamic<>(JsonOps.INSTANCE, spec.encode()));

   public ActionSpec {
      java.util.Objects.requireNonNull(id, "id");
      arguments = arguments.deepCopy();
      arguments.remove("id");
   }

   @Override public JsonObject arguments() { return arguments.deepCopy(); }

   public static ActionSpec parse(JsonElement encoded) {
      if (encoded == null) throw new IllegalArgumentException("Missing binding action");
      if (encoded.isJsonPrimitive() && encoded.getAsJsonPrimitive().isString()) {
         return new ActionSpec(parseId(encoded.getAsString()), new JsonObject());
      }
      if (!encoded.isJsonObject() || !encoded.getAsJsonObject().has("id")) {
         throw new IllegalArgumentException("Action must have an id");
      }
      JsonObject object = encoded.getAsJsonObject();
      return new ActionSpec(parseId(object.get("id").getAsString()), object);
   }

   public JsonObject encode() {
      JsonObject result = arguments();
      result.addProperty("id", id.toString());
      return result;
   }

   private static ResourceLocation parseId(String value) {
      ResourceLocation result = ResourceLocation.tryParse(value);
      if (result == null) throw new IllegalArgumentException("Invalid action id: " + value);
      return result;
   }
}
