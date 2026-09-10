package io.github.fastformer.fastplace.placement.effect;

import io.github.fastformer.fastplace.FastPlaceSettings;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import net.minecraft.resources.ResourceLocation;

/** The only registration and selection point for special placement effects. */
public final class PlacementEffectRegistry {
   private static final List<PlacementEffect> EFFECTS = loadEffects();

   private PlacementEffectRegistry() {
   }

   private static List<PlacementEffect> loadEffects() {
      return ServiceLoader.load(PlacementEffect.class, PlacementEffect.class.getClassLoader())
         .stream()
         .map(ServiceLoader.Provider::get)
         .sorted(Comparator.comparingInt(PlacementEffect::priority).reversed())
         .toList();
   }

   public static Optional<ResolvedPlacementEffect> resolve(
      FastPlaceSettings settings, PlacementEffectContext context
   ) {
      return EFFECTS.stream()
         .filter(effect -> effect.enabled(settings))
         .filter(effect -> effect.matches(context))
         .findFirst()
         .map(effect -> effect.resolve(context));
   }

   public static List<PlacementEffect> effects() {
      return EFFECTS;
   }

   public static Optional<ResolvedPlacementEffect> resolve(
      ResourceLocation effectId, PlacementEffectContext context
   ) {
      return EFFECTS.stream()
         .filter(effect -> effect.id().equals(effectId))
         .filter(effect -> effect.matches(context))
         .findFirst()
         .map(effect -> effect.resolve(context));
   }

   public static boolean contains(ResourceLocation id) {
      return id != null && EFFECTS.stream().anyMatch(effect -> effect.id().equals(id));
   }
}
