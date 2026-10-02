package io.github.fastformer.client;

import io.github.fastformer.fastplace.geometry.GeometryPalette;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only visual settings. */
@net.neoforged.fml.common.EventBusSubscriber(modid = "fastformer", value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class FastFormerClientConfig {
   public static final ModConfigSpec SPEC;
   private static final ModConfigSpec.EnumValue<GeometryPalette.Theme> THEME;

   static {
      ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
      builder.push("visual");
      THEME = builder.comment("Overlay theme: CLASSIC (white lines, vivid accents) or HUMANIST (hand-drawn pencil).")
         .defineEnum("theme", GeometryPalette.Theme.CLASSIC);
      builder.pop();
      SPEC = builder.build();
   }

   private FastFormerClientConfig() {
   }

   @net.neoforged.bus.api.SubscribeEvent
   public static void onConfig(net.neoforged.fml.event.config.ModConfigEvent event) {
      if (event.getConfig().getSpec() == SPEC) {
         GeometryPalette.selectTheme(theme());
      }
   }

   /** Falls back to the default before the config file loads, and on a dedicated server. */
   public static GeometryPalette.Theme theme() {
      if (!SPEC.isLoaded()) {
         return GeometryPalette.Theme.CLASSIC;
      }
      try {
         return THEME.get();
      } catch (IllegalStateException exception) {
         return GeometryPalette.Theme.CLASSIC;
      }
   }

   public static void setTheme(GeometryPalette.Theme theme) {
      if (!SPEC.isLoaded()) {
         return;
      }
      THEME.set(theme);
      GeometryPalette.selectTheme(theme);
      THEME.save();
   }
}
