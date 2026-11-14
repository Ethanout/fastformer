package io.github.fastformer.client.preview.style;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GeometryPaletteRevisionTest {
   @Test
   void switchesAndResourceReloadsAdvanceOneMonotonicRevision() {
      var original = GeometryPalette.theme();
      try {
         long initial = GeometryPalette.revision();
         GeometryPalette.selectTheme(original);
         assertEquals(initial, GeometryPalette.revision());
         var other = original == GeometryPalette.Theme.CLASSIC ? GeometryPalette.Theme.HUMANIST : GeometryPalette.Theme.CLASSIC;
         GeometryPalette.selectTheme(other);
         long switched = GeometryPalette.revision();
         assertTrue(switched > initial);
         GeometryPalette.selectTheme(original);
         long returned = GeometryPalette.revision();
         assertTrue(returned > switched);
         GeometryPalette.installThemes(Map.of());
         assertTrue(GeometryPalette.revision() > returned);
      } finally { GeometryPalette.selectTheme(original); }
   }
}
