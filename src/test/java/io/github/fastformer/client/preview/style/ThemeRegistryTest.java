package io.github.fastformer.client.preview.style;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ThemeRegistryTest {
   private static final ResourceLocation THIRD = ResourceLocation.parse("example:night/blue");

   @Test void threeThemesCycleWithoutRevisionCollisionsAndOldSnapshotsStayFrozen() {
      var registry = new ThemeRegistry();
      var third = theme(THIRD, "{\"version\":1,\"base\":\"fastformer:humanist\",\"colors\":{\"ink\":\"#123456\"}}");
      var loaded = new HashMap<>(Map.of(THIRD, third));
      registry.install(loaded);
      loaded.clear();
      var original = registry.snapshot();
      assertEquals(List.of(BuiltinThemes.CLASSIC, BuiltinThemes.HUMANIST, THIRD),
         original.definitions().stream().map(ThemeDefinition::id).toList());
      for (ResourceLocation id : List.of(BuiltinThemes.HUMANIST, THIRD, BuiltinThemes.CLASSIC)) {
         assertEquals(id, registry.snapshot().next());
         long previous = registry.snapshot().revision();
         registry.select(id);
         assertEquals(previous + 1, registry.snapshot().revision());
         assertEquals(id, registry.snapshot().selected().id());
      }
      registry.select(THIRD);
      assertEquals(0x123456, registry.snapshot().selected().color("ink", GeometryPalette.Swatches::ink).rgb());
      assertTrue(registry.snapshot().selected().humanist());
      assertEquals(0x9AB87A, registry.snapshot().selected().swatches().softY().rgb());
      assertEquals(BuiltinThemes.CLASSIC, original.selected().id());
      assertThrows(UnsupportedOperationException.class, () -> original.definitions().clear());
      long selected = registry.snapshot().revision();
      registry.select(THIRD);
      assertEquals(selected, registry.snapshot().revision());
   }

   @Test void reloadRemovalFallsBackAndReappearanceRestoresRequestedTheme() {
      var registry = new ThemeRegistry();
      registry.select(THIRD);
      assertEquals(THIRD, registry.snapshot().requested());
      assertEquals(BuiltinThemes.CLASSIC, registry.snapshot().selected().id());
      var first = theme(THIRD, "{\"version\":1,\"colors\":{\"ink\":\"#112233\"}}");
      registry.install(Map.of(THIRD, first));
      var beforeReload = registry.snapshot();
      assertEquals(THIRD, beforeReload.selected().id());
      registry.install(Map.of());
      assertEquals(BuiltinThemes.CLASSIC, registry.snapshot().selected().id());
      assertEquals(THIRD, registry.snapshot().requested());
      assertEquals(THIRD, beforeReload.selected().id());
      var updated = theme(THIRD, "{\"version\":1,\"colors\":{\"ink\":\"#445566\"}}");
      registry.install(Map.of(THIRD, updated));
      var restored = registry.snapshot();
      assertEquals(THIRD, restored.selected().id());
      assertEquals(0x445566, restored.selected().visual().colors().get("ink"));
      assertTrue(restored.revision() > beforeReload.revision());
      registry.invalidateGeometry();
      assertEquals(restored.revision() + 1, registry.snapshot().revision());
      assertSame(restored.selected(), registry.snapshot().selected());
   }

   @Test void malformedInstallCannotReplacePublishedSnapshot() {
      var registry = new ThemeRegistry();
      var previous = registry.snapshot();
      assertThrows(IllegalArgumentException.class, () -> registry.install(Map.of(THIRD, BuiltinThemes.definition(BuiltinThemes.CLASSIC))));
      assertSame(previous, registry.snapshot());
      assertThrows(IllegalArgumentException.class, () -> theme(THIRD, "{\"version\":1,\"base\":\"example:missing\"}"));
   }

   private static ThemeDefinition theme(ResourceLocation id, String json) {
      return ThemeDefinition.parse(id, JsonParser.parseString(json).getAsJsonObject());
   }
}
