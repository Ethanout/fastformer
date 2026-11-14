package io.github.fastformer.client.preview.style;

import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/** One complete theme, including fallback colors and validated resource values. */
public record ThemeDefinition(ResourceLocation id, String translationKey, GeometryPalette.Swatches swatches,
   boolean humanist, VisualTheme visual) {
   public ThemeDefinition {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(translationKey, "translationKey");
      Objects.requireNonNull(swatches, "swatches");
      Objects.requireNonNull(visual, "visual");
   }

   public static ThemeDefinition parse(ResourceLocation id, JsonObject json) {
      ResourceLocation baseId = json.has("base") ? ResourceLocation.parse(json.get("base").getAsString())
         : id.equals(BuiltinThemes.HUMANIST) ? BuiltinThemes.HUMANIST : BuiltinThemes.CLASSIC;
      ThemeDefinition base = BuiltinThemes.definition(baseId);
      String label = json.has("name") ? json.get("name").getAsString()
         : id.getNamespace() + ".settings.theme." + id.getPath().replace('/', '.');
      if (label.isBlank()) throw new IllegalArgumentException("Theme name must not be blank: " + id);
      return new ThemeDefinition(id, label, base.swatches(), base.humanist(), VisualTheme.parse(json));
   }

   public GeometryPalette.Color color(String key, GeometryPalette.Color fallback) {
      return new GeometryPalette.Color(visual.colors().getOrDefault(key, fallback.rgb()));
   }

   public GeometryPalette.Color color(String key, Function<GeometryPalette.Swatches, GeometryPalette.Color> fallback) {
      return color(key, fallback.apply(swatches));
   }

   public float value(String key, float fallback) { return visual.values().getOrDefault(key, fallback); }
   public DistanceCurve curve(String key) { return visual.curves().getOrDefault(key, DistanceCurve.CONSTANT); }
}
