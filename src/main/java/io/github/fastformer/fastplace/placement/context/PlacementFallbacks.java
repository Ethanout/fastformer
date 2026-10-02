package io.github.fastformer.fastplace.placement.context;

import io.github.fastformer.FastFormer;
import java.util.Comparator;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

public final class PlacementFallbacks {
   public static final ResourceKey<Registry<PlacementFallbackRule>> REGISTRY = ResourceKey.createRegistryKey(
      ResourceLocation.fromNamespaceAndPath(FastFormer.MOD_ID, "placement_fallback")
   );
   private static final Comparator<Map.Entry<ResourceKey<PlacementFallbackRule>, PlacementFallbackRule>> ORDER =
      Comparator.<Map.Entry<ResourceKey<PlacementFallbackRule>, PlacementFallbackRule>>comparingInt(entry -> entry.getValue().priority())
         .reversed().thenComparing(entry -> entry.getKey().location().toString());

   private PlacementFallbacks() {}

   public static void register(DataPackRegistryEvent.NewRegistry event) {
      event.dataPackRegistry(REGISTRY, PlacementFallbackRule.CODEC, PlacementFallbackRule.CODEC);
   }

   public static BlockState resolve(BlockState defaultState, BlockPlaceContext context) {
      return context.getLevel().registryAccess().registry(REGISTRY)
         .map(registry -> resolve(registry, defaultState, context)).orElse(defaultState);
   }

   public static BlockState resolve(Registry<PlacementFallbackRule> rules, BlockState defaultState, BlockPlaceContext context) {
      return rules.entrySet().stream().sorted(ORDER)
         .flatMap(entry -> entry.getValue().apply(defaultState, context).stream())
         .findFirst().orElse(defaultState);
   }
}
