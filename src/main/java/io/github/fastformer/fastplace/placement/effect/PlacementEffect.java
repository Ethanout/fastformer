package io.github.fastformer.fastplace.placement.effect;

import io.github.fastformer.fastplace.FastPlaceSettings;
import net.minecraft.resources.ResourceLocation;

/** One registered special placement behavior. */
public interface PlacementEffect {
   ResourceLocation id();

   String translationKey();

   int priority();

   boolean enabled(FastPlaceSettings settings);

   boolean matches(PlacementEffectContext context);

   ResolvedPlacementEffect resolve(PlacementEffectContext context);
}
