package io.github.fastformer.client;

import io.github.fastformer.fastplace.geometry.GeometryPalette;
import io.github.fastformer.fastplace.session.SessionActivationMode;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client preferences for presentation and session activation. */
@net.neoforged.fml.common.EventBusSubscriber(modid = "fastformer", value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class FastFormerClientConfig {
   public static final ModConfigSpec SPEC;
   private static final ModConfigSpec.EnumValue<GeometryPalette.Theme> THEME;
   private static final ModConfigSpec.EnumValue<SessionActivationMode> ACTIVATION_MODE;

   static {
      ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
      builder.push("visual");
      THEME = builder.comment("Overlay theme: CLASSIC (white lines, vivid accents) or HUMANIST (hand-drawn pencil).")
         .defineEnum("theme", GeometryPalette.Theme.CLASSIC);
      builder.pop();
      builder.push("interaction");
      ACTIVATION_MODE = builder.comment("Session activation: MANUAL (Q with an empty main hand) or AUTOMATIC (distance).")
         .defineEnum("activationMode", SessionActivationMode.MANUAL);
      builder.pop();
      SPEC = builder.build();
   }

   private FastFormerClientConfig() {
   }

   public static SessionActivationMode activationMode() {
      return SPEC.isLoaded() ? ACTIVATION_MODE.get() : SessionActivationMode.MANUAL;
   }

   public static void setActivationMode(SessionActivationMode mode) {
      if (!SPEC.isLoaded()) return;
      ACTIVATION_MODE.set(mode);
      ACTIVATION_MODE.save();
      io.github.fastformer.client.input.FastPlaceClientInput.configureActivationMode(mode);
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
