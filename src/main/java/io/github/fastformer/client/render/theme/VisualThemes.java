package io.github.fastformer.client.render.theme;

import com.mojang.logging.LogUtils;
import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.client.preview.style.DistanceCurve;
import io.github.fastformer.client.preview.style.ThemeDefinition;
import java.util.HashMap;
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

   private VisualThemes() { }

   public static long revision() { return GeometryPalette.revision(); }

   public static void invalidateGeometry() { GeometryPalette.invalidateGeometry(); }

   public static GeometryPalette.Color color(String name, GeometryPalette.Color fallback) {
      return GeometryPalette.color(name, fallback);
   }

   public static float value(String name, float fallback) {
      return GeometryPalette.value(name, fallback);
   }

   public static DistanceCurve curve(String name) {
      return GeometryPalette.renderSnapshot().selected().curve(name);
   }

   @SubscribeEvent
   public static void register(RegisterClientReloadListenersEvent event) {
      event.registerReloadListener(new Loader());
   }

   private static final class Loader extends SimplePreparableReloadListener<Map<ResourceLocation, ThemeDefinition>> {
      private static final String DIRECTORY = "visual/themes/";
      @Override
      protected Map<ResourceLocation, ThemeDefinition> prepare(ResourceManager resources, ProfilerFiller profiler) {
         Map<ResourceLocation, ThemeResources.Source> sources = new HashMap<>();
         resources.listResources("visual/themes", id -> id.getPath().endsWith(".json")).forEach((file, resource) -> {
            String path = file.getPath();
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
               path.substring(DIRECTORY.length(), path.length() - ".json".length()));
            sources.put(id, resource::openAsReader);
         });
         return ThemeResources.load(sources, (id, failure) ->
            LogUtils.getLogger().warn("Cannot load visual theme {}; using built-in defaults when available", id, failure));
      }

      @Override
      protected void apply(Map<ResourceLocation, ThemeDefinition> loaded, ResourceManager resources, ProfilerFiller profiler) {
         GeometryPalette.installThemes(loaded);
      }
   }
}
