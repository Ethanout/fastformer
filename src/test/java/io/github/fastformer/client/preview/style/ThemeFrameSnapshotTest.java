package io.github.fastformer.client.preview.style;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.github.fastformer.client.render.theme.VisualThemes;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ThemeFrameSnapshotTest {
   @Test void selectionAndReloadDuringDrawBecomeVisibleTogetherOnTheNextFrame() {
      var previous = GeometryPalette.snapshot();
      var third = ResourceLocation.parse("example:blue");
      var definition = ThemeDefinition.parse(third,
         JsonParser.parseString("""
            {"version":1,"colors":{"ink":"#123456"},"values":{"line_width":2},
             "curves":{"line_width":[[0,2],[10,3]]}}
            """).getAsJsonObject());
      try {
         GeometryPalette.installThemes(Map.of());
         GeometryPalette.selectTheme(BuiltinThemes.CLASSIC);
         GeometryPalette.beginFrame();
         var frame = GeometryPalette.renderSnapshot();
         GeometryPalette.installThemes(Map.of(third, definition));
         GeometryPalette.selectTheme(third);
         assertSame(frame, GeometryPalette.renderSnapshot());
         assertEquals(frame.revision(), GeometryPalette.revision());
         assertEquals(frame.revision(), VisualThemes.revision());
         assertEquals(0xFFFFFF, GeometryPalette.ink().rgb());
         assertEquals(0xFFFFFF, VisualThemes.color("ink", new GeometryPalette.Color(0xFFFFFF)).rgb());
         assertEquals(1, GeometryPalette.value("line_width", 1));
         assertEquals(1, VisualThemes.curve("line_width").at(0));
         assertEquals(third, GeometryPalette.snapshot().selected().id());
         GeometryPalette.endFrame();
         GeometryPalette.beginFrame();
         assertEquals(third, GeometryPalette.renderSnapshot().selected().id());
         assertEquals(0x123456, GeometryPalette.ink().rgb());
         assertEquals(2, GeometryPalette.value("line_width", 1));
         assertEquals(2, VisualThemes.value("line_width", 1));
         assertEquals(2, VisualThemes.curve("line_width").at(0));
         assertTrue(GeometryPalette.revision() > frame.revision());
      } finally {
         GeometryPalette.endFrame();
         var definitions = new java.util.HashMap<ResourceLocation, ThemeDefinition>();
         previous.definitions().forEach(theme -> definitions.put(theme.id(), theme));
         GeometryPalette.installThemes(definitions);
         GeometryPalette.selectTheme(previous.requested());
      }
   }
}
