package io.github.fastformer.client.render.theme;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import io.github.fastformer.fastplace.geometry.VisualTheme;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Publishes complete theme snapshots after a resource reload. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class VisualThemes {
   private static volatile Map<GeometryPalette.Theme, VisualTheme> themes = Map.of();
   private static volatile long revision;

   private VisualThemes() { }

   public static long revision() { return revision * 2 + GeometryPalette.theme().ordinal(); }

   public static GeometryPalette.Color color(String name, GeometryPalette.Color fallback) {
      return new GeometryPalette.Color(current().colors().getOrDefault(name, fallback.rgb()));
   }

   public static float value(String name, float fallback) {
      return current().values().getOrDefault(name, fallback);
   }

   public static io.github.fastformer.fastplace.geometry.DistanceCurve curve(String name) {
      return current().curves().getOrDefault(name, io.github.fastformer.fastplace.geometry.DistanceCurve.CONSTANT);
   }

   private static VisualTheme current() {
      return themes.getOrDefault(GeometryPalette.theme(), VisualTheme.EMPTY);
   }

   @SubscribeEvent
   public static void register(RegisterClientReloadListenersEvent event) {
      event.registerReloadListener(new Loader());
   }

   private static final class Loader extends SimplePreparableReloadListener<Map<GeometryPalette.Theme, VisualTheme>> {
      @Override
      protected Map<GeometryPalette.Theme, VisualTheme> prepare(ResourceManager resources, ProfilerFiller profiler) {
         var loaded = new EnumMap<GeometryPalette.Theme, VisualTheme>(GeometryPalette.Theme.class);
         for (var theme : GeometryPalette.Theme.values()) {
            var id = ResourceLocation.fromNamespaceAndPath("fastformer", "visual/themes/" + theme.name().toLowerCase(Locale.ROOT) + ".json");
            try (var reader = resources.openAsReader(id)) {
               loaded.put(theme, VisualTheme.parse(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (IOException | RuntimeException failure) {
               LogUtils.getLogger().warn("Cannot load visual theme {}; using built-in defaults", id, failure);
            }
         }
         return Map.copyOf(loaded);
      }

      @Override
      protected void apply(Map<GeometryPalette.Theme, VisualTheme> loaded, ResourceManager resources, ProfilerFiller profiler) {
         themes = loaded;
         GeometryPalette.installThemes(loaded);
         revision++;
         io.github.fastformer.client.render.core.FastPlaceClientPreviewCore.onThemeChanged();
      }
   }
}
