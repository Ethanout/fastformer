package io.github.fastformer.client.render.theme;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.preview.style.BuiltinThemes;
import io.github.fastformer.client.preview.style.ThemeRegistry;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ThemeResourcesTest {
   @Test void badJsonAndUnreadableResourcesDoNotDiscardValidThemes() {
      var third = ResourceLocation.parse("example:night/blue");
      var broken = ResourceLocation.parse("example:broken");
      var unreadable = ResourceLocation.parse("example:unreadable");
      var diagnostics = new ArrayList<ResourceLocation>();
      var loaded = ThemeResources.load(Map.of(
         BuiltinThemes.CLASSIC, () -> new StringReader("{\"version\":1,\"colors\":{\"ink\":\"#AABBCC\"}}"),
         BuiltinThemes.HUMANIST, () -> new StringReader("{\"version\":2}"),
         third, () -> new StringReader("{\"version\":1,\"base\":\"fastformer:humanist\"}"),
         broken, () -> new StringReader("{ broken"),
         unreadable, () -> { throw new IOException("resource unavailable"); }
      ), (id, error) -> diagnostics.add(id));
      assertEquals(3, diagnostics.size());
      assertTrue(diagnostics.containsAll(java.util.List.of(BuiltinThemes.HUMANIST, broken, unreadable)));
      var registry = new ThemeRegistry();
      registry.install(loaded);
      var snapshot = registry.snapshot();
      assertEquals(3, snapshot.definitions().size());
      assertEquals(0xAABBCC, snapshot.selected().visual().colors().get("ink"));
      registry.select(BuiltinThemes.HUMANIST);
      assertEquals(0xF4F1EA, registry.snapshot().selected().swatches().ink().rgb());
      registry.select(third);
      assertTrue(registry.snapshot().selected().humanist());
      assertThrows(UnsupportedOperationException.class, () -> loaded.clear());
   }
}
