package io.github.fastformer.client;

import io.github.fastformer.fastplace.geometry.GeometryPalette;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client presentation and local input preferences. */
@net.neoforged.fml.common.EventBusSubscriber(modid = "fastformer", value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class FastFormerClientConfig {
   public static final ModConfigSpec SPEC;
   private static final ModConfigSpec.EnumValue<GeometryPalette.Theme> THEME;
   private static final int DEFAULT_SCROLL_HISTORY_MERGE_MILLIS = 250;
   private static final ModConfigSpec.IntValue SCROLL_HISTORY_MERGE_MILLIS;

   static {
      ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
      builder.push("visual");
      THEME = builder.comment("Overlay theme: CLASSIC (white lines, vivid accents) or HUMANIST (hand-drawn pencil).")
         .defineEnum("theme", GeometryPalette.Theme.CLASSIC);
      builder.pop();
      builder.push("selection");
      SCROLL_HISTORY_MERGE_MILLIS = builder.comment("Merge consecutive scroll moves of the same selection and direction within this interval. Zero disables merging.")
         .defineInRange("scrollHistoryMergeMillis", DEFAULT_SCROLL_HISTORY_MERGE_MILLIS, 0, 2000);
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

   public static long scrollHistoryMergeNanos() {
      int millis = SPEC.isLoaded() ? SCROLL_HISTORY_MERGE_MILLIS.get() : DEFAULT_SCROLL_HISTORY_MERGE_MILLIS;
      return java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
   }
}
