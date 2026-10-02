package io.github.fastformer.fastplace.geometry;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisualThemeTest {
   @Test
   void packCanOverrideOnlySelectedValues() {
      var theme = parse("""
         {"version":1,"colors":{"ink":"#f4F1EA"},"values":{"candidate_alpha":0.4}}
         """);
      assertEquals(0xF4F1EA, theme.colors().get("ink"));
      assertEquals(0.4F, theme.values().get("candidate_alpha"));
      assertFalse(theme.values().containsKey("confirmed_alpha"));
      assertThrows(UnsupportedOperationException.class, () -> theme.colors().put("ink", 0));
   }

   @Test
   void rejectsMalformedOrInvisibleStageConfiguration() {
      assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":2}"));
      assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":1,\"colors\":{\"ink\":\"white\"}}"));
      assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":1,\"values\":{\"candidate_alpha\":1.2}}"));
      assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":1,\"values\":{\"pending_min_alpha\":0.8}}"));
      assertThrows(IllegalArgumentException.class, () -> parse("{\"version\":1,\"values\":{\"line_width\":-1}}"));
   }

   @Test
   void paletteCanLoadWithoutClientRuntime() {
      GeometryPalette.selectTheme(GeometryPalette.Theme.CLASSIC);
      GeometryPalette.installThemes(java.util.Map.of(GeometryPalette.Theme.CLASSIC,
         parse("{\"version\":1,\"colors\":{\"ink\":\"#123456\"}}")));
      try {
         assertEquals(0x123456, GeometryPalette.ink().rgb());
         assertEquals(0.85F, GeometryPalette.value("confirmed_alpha", 0.85F));
         GeometryPalette.selectTheme(GeometryPalette.Theme.HUMANIST);
         assertEquals(0xF4F1EA, GeometryPalette.ink().rgb());
      } finally {
         GeometryPalette.selectTheme(GeometryPalette.Theme.CLASSIC);
         GeometryPalette.installThemes(java.util.Map.of());
      }
   }

   private static VisualTheme parse(String json) {
      return VisualTheme.parse(JsonParser.parseString(json).getAsJsonObject());
   }
}
