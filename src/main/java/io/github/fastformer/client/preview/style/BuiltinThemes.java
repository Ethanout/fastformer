package io.github.fastformer.client.preview.style;

import net.minecraft.resources.ResourceLocation;

/** Fallback colors remain available before resource packs load. */
public final class BuiltinThemes {
   public static final ResourceLocation CLASSIC = ResourceLocation.fromNamespaceAndPath("fastformer", "classic");
   public static final ResourceLocation HUMANIST = ResourceLocation.fromNamespaceAndPath("fastformer", "humanist");
   private static final GeometryPalette.Swatches CLASSIC_COLORS = new GeometryPalette.Swatches(
      0xFFFFFF, 0x000000, 0.35F, 0xFFC71F, 0xFF5A5A, 0x55FF55,
      0xFFFFFF, 0xAAAAAA, 0xFFD166, 0x55FF55, 0xFF5555,
      0x203038, 0xFFFFFF, 0xB9D7E8,
      0xFF8A94, 0x8FEB9E, 0x8AB3FF,
      0xFF382E, 0x40FF59, 0x408CFF, 0xFFFFFF,
      0xFFFFFF, 0xE0E7ED);
   private static final GeometryPalette.Swatches HUMANIST_COLORS = new GeometryPalette.Swatches(
      0xF4F1EA, 0x2E2A26, 0.30F, 0xD9B36A, 0xD0735F, 0x9FB87C,
      0xF2F1EC, 0xB5B3AD, 0xD9B36A, 0x9FB87C, 0xD0735F,
      0xF4F2EC, 0x2E2A26, 0x6E6D6A,
      0xD0806C, 0x9AB87A, 0x88A2C4,
      0xD0806C, 0x9AB87A, 0x88A2C4, 0xD9B36A,
      0xF2F1EC, 0xB5B3AD);

   private BuiltinThemes() {}

   public static ThemeDefinition definition(ResourceLocation id) {
      if (CLASSIC.equals(id)) return new ThemeDefinition(id, "fastformer.settings.theme.classic", CLASSIC_COLORS, false, VisualTheme.EMPTY);
      if (HUMANIST.equals(id)) return new ThemeDefinition(id, "fastformer.settings.theme.humanist", HUMANIST_COLORS, true, VisualTheme.EMPTY);
      throw new IllegalArgumentException("Unknown built-in theme base: " + id);
   }
}
