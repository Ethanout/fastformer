package io.github.fastformer.client.render.theme;

import com.google.gson.JsonParser;
import io.github.fastformer.client.preview.style.ThemeDefinition;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;

/** Reads each resource independently; a bad entry cannot discard the rest of a reload. */
public final class ThemeResources {
   private ThemeResources() {}

   @FunctionalInterface
   public interface Source { Reader open() throws IOException; }

   public static Map<ResourceLocation, ThemeDefinition> load(Map<ResourceLocation, Source> sources,
      BiConsumer<ResourceLocation, Exception> diagnostics) {
      Map<ResourceLocation, ThemeDefinition> loaded = new HashMap<>();
      sources.forEach((id, source) -> {
         try (Reader reader = source.open()) {
            loaded.put(id, ThemeDefinition.parse(id, JsonParser.parseReader(reader).getAsJsonObject()));
         } catch (IOException | RuntimeException failure) {
            diagnostics.accept(id, failure);
         }
      });
      return Map.copyOf(loaded);
   }
}
