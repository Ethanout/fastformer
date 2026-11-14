package io.github.fastformer.server.specialitem;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SpecialItemRegistryTest {
   @Test void frozenRegistryDispatchesRegisteredHandlerAndRejectsDuplicatesAndLateChanges() {
      var registry = new SpecialItemRegistry();
      var id = ResourceLocation.parse("example:picker");
      SpecialItemHandlers.UseOnBlock handler = (player, point) -> point.equals(BlockPos.ZERO);
      registry.register(id, handler);
      assertThrows(IllegalArgumentException.class, () -> registry.register(id, (player, point) -> false));
      assertSame(handler, registry.handler(id).orElseThrow());
      registry.freeze();
      registry.freeze();
      assertTrue(registry.handler(id).orElseThrow().use(null, BlockPos.ZERO));
      assertFalse(registry.handler(id).orElseThrow().use(null, new BlockPos(1, 2, 3)));
      assertTrue(registry.handler(ResourceLocation.parse("example:unknown")).isEmpty());
      assertThrows(IllegalStateException.class, () -> registry.register(ResourceLocation.parse("example:late"), handler));
   }

   @Test void nullRegistrationCannotDamageExistingHandlers() {
      var registry = new SpecialItemRegistry();
      var id = ResourceLocation.parse("example:picker");
      assertThrows(NullPointerException.class, () -> registry.register(id, null));
      assertTrue(registry.handler(id).isEmpty());
      assertThrows(NullPointerException.class, () -> registry.register(null, (player, point) -> true));
   }
}
