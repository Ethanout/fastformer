package io.github.fastformer.common.mode;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.core.registry.Registry;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ModeIdTest {
   @Test void identitiesUseNamespacedValuesAndRejectDuplicateRegistrations() {
      ModeId first = ModeId.of("quick_replace");
      assertEquals(BuiltinModes.QUICK_REPLACE, first);
      assertEquals(new ModeId(ResourceLocation.parse("fastformer:quick_replace")), first);
      var registry = Registry.<ModeId, String>create("test modes");
      registry.register(first, "first");
      assertThrows(IllegalArgumentException.class, () -> registry.register(BuiltinModes.QUICK_REPLACE, "second"));
      assertEquals("first", registry.get(first).orElseThrow());
      registry.freeze();
      assertThrows(IllegalStateException.class, () -> registry.register(BuiltinModes.SMART_FILL, "fill"));
      assertThrows(NullPointerException.class, () -> new ModeId(null));
   }

   @Test void everyGeometryIdentityIsExplicitAndUnique() {
      assertEquals(GeometryMode.values().length,
         java.util.Arrays.stream(GeometryMode.values()).map(BuiltinModes::geometry).distinct().count());
      assertEquals(ModeId.of("geometry/cone_prism"), BuiltinModes.geometry(GeometryMode.CONE_PRISM));
   }
}
