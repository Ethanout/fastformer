package io.github.fastformer.fastplace.geometry;

import java.util.Map;

/**
 * Colors for the overlay, chosen by the client {@link Theme}.
 *
 * <p>World marks use {@link #ink()} over a faint {@link #halo()}, so they read on any background.
 * Color carries meaning only: {@link #ochre()} is hover or draggable, {@link #brick()} is conflict,
 * {@link #sage()} is valid.
 */
public final class GeometryPalette {
   private static volatile Theme selectedTheme = Theme.CLASSIC;
   private static volatile Map<Theme, VisualTheme> resources = Map.of();
   private GeometryPalette() { }

   public enum Theme {
      /** White lines, saturated accents with high separation. */
      CLASSIC(new Swatches(
         0xFFFFFF, 0x000000, 0.35F, 0xFFC71F, 0xFF5A5A, 0x55FF55,
         0xFFFFFF, 0xAAAAAA, 0xFFD166, 0x55FF55, 0xFF5555,
         0x203038, 0xFFFFFF, 0xB9D7E8,
         0xFF8A94, 0x8FEB9E, 0x8AB3FF,
         0xFF382E, 0x40FF59, 0x408CFF, 0xFFFFFF,
         0xFFFFFF, 0xE0E7ED
      )),
      /** Warm white pencil over a graphite halo, muted colored pencils. */
      HUMANIST(new Swatches(
         0xF4F1EA, 0x2E2A26, 0.30F, 0xD9B36A, 0xD0735F, 0x9FB87C,
         0xF2F1EC, 0xB5B3AD, 0xD9B36A, 0x9FB87C, 0xD0735F,
         0xF4F2EC, 0x2E2A26, 0x6E6D6A,
         0xD0806C, 0x9AB87A, 0x88A2C4,
         0xD0806C, 0x9AB87A, 0x88A2C4, 0xD9B36A,
         0xF2F1EC, 0xB5B3AD
      ));

      private final Swatches swatches;

      Theme(Swatches swatches) {
         this.swatches = swatches;
      }

      public Swatches swatches() { return this.swatches; }

      public String translationKey() {
         return "fastformer.settings.theme." + name().toLowerCase(java.util.Locale.ROOT);
      }

      public Theme next() {
         return values()[(ordinal() + 1) % values().length];
      }
   }

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

   public static Theme theme() { return selectedTheme; }
   public static void selectTheme(Theme theme) { selectedTheme = java.util.Objects.requireNonNull(theme); }
   public static void installThemes(Map<Theme, VisualTheme> themes) { resources = Map.copyOf(themes); }
   public static Color color(String key, Color fallback) {
      return new Color(resources.getOrDefault(theme(), VisualTheme.EMPTY).colors().getOrDefault(key, fallback.rgb()));
   }
   public static float value(String key, float fallback) {
      return resources.getOrDefault(theme(), VisualTheme.EMPTY).values().getOrDefault(key, fallback);
   }
   public static boolean humanist() { return theme() == Theme.HUMANIST; }
   private static Swatches s() { return theme().swatches(); }

   public static Color ink() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("ink", s().ink()); }
   public static Color halo() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("halo", s().halo()); }
   public static float haloAlpha() { return io.github.fastformer.fastplace.geometry.GeometryPalette.value("halo_alpha", s().haloAlpha()); }
   public static Color ochre() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("ochre", s().ochre()); }
   public static Color brick() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("brick", s().brick()); }
   public static Color sage() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("sage", s().sage()); }
   public static Color text() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("text", s().text()); }
   public static Color muted() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("muted", s().muted()); }
   public static Color accent() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("accent", s().accent()); }
   public static Color valid() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("valid", s().valid()); }
   public static Color danger() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("danger", s().danger()); }
   /** Paper behind world labels, with {@link #paperInk()} text. */
   public static Color paper() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("paper", s().paper()); }
   public static Color paperInk() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("paperInk", s().paperInk()); }
   public static Color paperMuted() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("paperMuted", s().paperMuted()); }
   public static Color gizmoHover() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("gizmoHover", s().gizmoHover()); }
   public static Color screenTitle() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("screenTitle", s().screenTitle()); }
   public static Color screenLabel() { return io.github.fastformer.fastplace.geometry.GeometryPalette.color("screenLabel", s().screenLabel()); }

   /** Axis color for soft in-world axis hints. */
   public static Color softAxis(AxisGizmo.Axis axis) {
      Swatches swatches = s();
      return switch (axis) {
         case X -> io.github.fastformer.fastplace.geometry.GeometryPalette.color("softX", swatches.softX());
         case Y -> io.github.fastformer.fastplace.geometry.GeometryPalette.color("softY", swatches.softY());
         case Z -> io.github.fastformer.fastplace.geometry.GeometryPalette.color("softZ", swatches.softZ());
      };
   }

   /** Axis color for gizmo handles. */
   public static Color gizmoAxis(AxisGizmo.Axis axis) {
      Swatches swatches = s();
      return switch (axis) {
         case X -> io.github.fastformer.fastplace.geometry.GeometryPalette.color("gizmoX", swatches.gizmoX());
         case Y -> io.github.fastformer.fastplace.geometry.GeometryPalette.color("gizmoY", swatches.gizmoY());
         case Z -> io.github.fastformer.fastplace.geometry.GeometryPalette.color("gizmoZ", swatches.gizmoZ());
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
