package io.github.fastformer.fastplace.geometry;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/** Immutable, validated resource-pack values. Missing fields use the bundled defaults. */
public record VisualTheme(Map<String, Integer> colors, Map<String, Float> values, Map<String, DistanceCurve> curves) {
   public static final VisualTheme EMPTY = new VisualTheme(Map.of(), Map.of(), Map.of());

   public VisualTheme {
      curves = Map.copyOf(curves);
      colors = Map.copyOf(colors);
      values = Map.copyOf(values);
   }

   public static VisualTheme parse(JsonObject json) {
      if (!json.has("version") || json.get("version").getAsInt() != 1) {
         throw new IllegalArgumentException("Visual theme version must be 1");
      }
      Map<String, Integer> colors = new HashMap<>();
      if (json.has("colors")) json.getAsJsonObject("colors").entrySet().forEach(entry -> {
         String value = entry.getValue().getAsString();
         if (!value.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Invalid color: " + entry.getKey());
         colors.put(entry.getKey(), Integer.parseInt(value.substring(1), 16));
      });
      Map<String, Float> values = new HashMap<>();
      if (json.has("values")) json.getAsJsonObject("values").entrySet().forEach(entry -> {
         float value = entry.getValue().getAsFloat();
         if (!Float.isFinite(value) || value < 0 || value > 32) {
            throw new IllegalArgumentException("Visual value must be finite and between 0 and 32: " + entry.getKey());
         }
         if (entry.getKey().endsWith("alpha") && value > 1) {
            throw new IllegalArgumentException("Alpha must be between 0 and 1: " + entry.getKey());
         }
         values.put(entry.getKey(), value);
      });
      float min = values.getOrDefault("pending_min_alpha", 0.05F);
      float max = values.getOrDefault("pending_max_alpha", 0.30F);
      if (min > max) throw new IllegalArgumentException("Pending minimum alpha exceeds maximum alpha");
      Map<String, DistanceCurve> curves = new HashMap<>();
      if (json.has("curves")) json.getAsJsonObject("curves").entrySet().forEach(entry ->
         curves.put(entry.getKey(), DistanceCurve.parse(entry.getValue().getAsJsonArray())));
      return new VisualTheme(colors, values, curves);
   }
}
