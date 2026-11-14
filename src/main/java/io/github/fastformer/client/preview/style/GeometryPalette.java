package io.github.fastformer.client.preview.style;

import io.github.fastformer.core.math.Axis;

import java.util.Map;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/**
 * Colors for the overlay, chosen from one published theme definition.
 *
 * <p>World marks use {@link #ink()} over a faint {@link #halo()}, so they read on any background.
 * Color carries meaning only: {@link #ochre()} is hover or draggable, {@link #brick()} is conflict,
 * {@link #sage()} is valid.
 */
public final class GeometryPalette {
   private static final ThemeRegistry THEMES = new ThemeRegistry();
   private static final ThreadLocal<ThemeRegistry.Snapshot> FRAME_THEME = new ThreadLocal<>();
   private GeometryPalette() { }

   /** One theme's colors. World marks come first, then screen text, labels, and axes. */
   public record Swatches(
      Color ink, Color halo, float haloAlpha, Color ochre, Color brick, Color sage,
      Color text, Color muted, Color accent, Color valid, Color danger,
      Color paper, Color paperInk, Color paperMuted,
      Color softX, Color softY, Color softZ,
      Color gizmoX, Color gizmoY, Color gizmoZ, Color gizmoHover,
      Color screenTitle, Color screenLabel
   ) {
      Swatches(
         int ink, int halo, float haloAlpha, int ochre, int brick, int sage,
         int text, int muted, int accent, int valid, int danger,
         int paper, int paperInk, int paperMuted,
         int softX, int softY, int softZ,
         int gizmoX, int gizmoY, int gizmoZ, int gizmoHover,
         int screenTitle, int screenLabel
      ) {
         this(new Color(ink), new Color(halo), haloAlpha, new Color(ochre), new Color(brick), new Color(sage),
            new Color(text), new Color(muted), new Color(accent), new Color(valid), new Color(danger),
            new Color(paper), new Color(paperInk), new Color(paperMuted),
            new Color(softX), new Color(softY), new Color(softZ),
            new Color(gizmoX), new Color(gizmoY), new Color(gizmoZ), new Color(gizmoHover),
            new Color(screenTitle), new Color(screenLabel));
      }
   }

   public static ThemeRegistry.Snapshot snapshot() { return THEMES.snapshot(); }
   /** Pins draw values until the render-frame post event, even if a subscriber reloads a theme. */
   public static void beginFrame() { FRAME_THEME.set(snapshot()); }
   public static void endFrame() { FRAME_THEME.remove(); }
   public static ThemeRegistry.Snapshot renderSnapshot() {
      var frame = FRAME_THEME.get();
      return frame == null ? snapshot() : frame;
   }
   public static ResourceLocation theme() { return snapshot().selected().id(); }
   public static long revision() { return renderSnapshot().revision(); }
   public static void selectTheme(ResourceLocation theme) { THEMES.select(theme); }
   public static void installThemes(Map<ResourceLocation, ThemeDefinition> themes) { THEMES.install(themes); }
   public static void invalidateGeometry() { THEMES.invalidateGeometry(); }
   public static Color color(String key, Color fallback) {
      return renderSnapshot().selected().color(key, fallback);
   }
   public static float value(String key, float fallback) {
      return renderSnapshot().selected().value(key, fallback);
   }
   public static boolean humanist() { return renderSnapshot().selected().humanist(); }
   private static Color color(String key, Function<Swatches, Color> fallback) {
      return renderSnapshot().selected().color(key, fallback);
   }

   public static Color ink() { return color("ink", Swatches::ink); }
   public static Color confirmedOutline() { return color("confirmedOutline", Swatches::ink); }
   public static Color pendingOutline() { return color("pendingOutline", Swatches::ink); }
   public static Color halo() { return color("halo", Swatches::halo); }
   public static float haloAlpha() {
      ThemeDefinition theme = renderSnapshot().selected();
      return theme.value("halo_alpha", theme.swatches().haloAlpha());
   }
   public static Color ochre() { return color("ochre", Swatches::ochre); }
   public static Color brick() { return color("brick", Swatches::brick); }
   public static Color sage() { return color("sage", Swatches::sage); }
   public static Color text() { return color("text", Swatches::text); }
   public static Color muted() { return color("muted", Swatches::muted); }
   public static Color accent() { return color("accent", Swatches::accent); }
   public static Color valid() { return color("valid", Swatches::valid); }
   public static Color danger() { return color("danger", Swatches::danger); }
   /** Paper behind world labels, with {@link #paperInk()} text. */
   public static Color paper() { return color("paper", Swatches::paper); }
   public static Color paperInk() { return color("paperInk", Swatches::paperInk); }
   public static Color paperMuted() { return color("paperMuted", Swatches::paperMuted); }
   public static Color gizmoHover() { return color("gizmoHover", Swatches::gizmoHover); }
   public static Color screenTitle() { return color("screenTitle", Swatches::screenTitle); }
   public static Color screenLabel() { return color("screenLabel", Swatches::screenLabel); }

   /** Axis color for soft in-world axis hints. */
   public static Color softAxis(Axis axis) {
      return switch (axis) {
         case X -> color("softX", Swatches::softX);
         case Y -> color("softY", Swatches::softY);
         case Z -> color("softZ", Swatches::softZ);
      };
   }

   /** Axis color for gizmo handles. */
   public static Color gizmoAxis(Axis axis) {
      return switch (axis) {
         case X -> color("gizmoX", Swatches::gizmoX);
         case Y -> color("gizmoY", Swatches::gizmoY);
         case Z -> color("gizmoZ", Swatches::gizmoZ);
      };
   }

   public record Color(int rgb) {
      public float red() { return ((rgb >>> 16) & 255) / 255.0F; }
      public float green() { return ((rgb >>> 8) & 255) / 255.0F; }
      public float blue() { return (rgb & 255) / 255.0F; }
      public int argb() { return argb(255); }
      public int argb(int alpha) { return Math.clamp(alpha, 0, 255) << 24 | rgb; }
      public net.minecraft.network.chat.Style style() { return net.minecraft.network.chat.Style.EMPTY.withColor(rgb); }
   }
}
