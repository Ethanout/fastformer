package io.github.fastformer.fastplace.placement.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.placement.effect.woodframe.WoodFramePlacementEffect;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class PlacementEffectRegistryTest {
   @Test
   void registryContainsOnlyRegisteredEffectIds() {
      assertTrue(PlacementEffectRegistry.contains(WoodFramePlacementEffect.ID));
      assertFalse(PlacementEffectRegistry.contains(
         ResourceLocation.fromNamespaceAndPath("fastformer", "not_registered")
      ));
      assertFalse(PlacementEffectRegistry.contains(null));
   }

   @Test
   void registeredEffectsAreOrderedByPriority() {
      assertEquals(
         WoodFramePlacementEffect.ID,
         PlacementEffectRegistry.effects().getFirst().id()
      );
   }
}
