package io.github.fastformer.common.placement.effect;

import net.minecraft.resources.ResourceLocation;

/** One registered special placement behavior. */
public interface PlacementEffect {
   ResourceLocation id();

   String translationKey();

   int priority();

   boolean matches(PlacementEffectContext context);

   ResolvedPlacementEffect resolve(PlacementEffectContext context);
}
