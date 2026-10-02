package io.github.fastformer.fastplace.interaction;

import io.github.fastformer.FastFormer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.BlockFamilies;
import net.minecraft.data.BlockFamily;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

public final class TinkerShapeFamilies {
   public static final ResourceKey<Registry<TinkerShapeFamily>> REGISTRY = ResourceKey.createRegistryKey(
      ResourceLocation.fromNamespaceAndPath(FastFormer.MOD_ID, "tinker_shape")
   );
   private static final Comparator<Map.Entry<ResourceKey<TinkerShapeFamily>, TinkerShapeFamily>> ORDER =
      Comparator.<Map.Entry<ResourceKey<TinkerShapeFamily>, TinkerShapeFamily>>comparingInt(entry -> entry.getValue().priority())
         .reversed().thenComparing(entry -> entry.getKey().location().toString());

   private TinkerShapeFamilies() {}

   public static void register(DataPackRegistryEvent.NewRegistry event) {
      event.dataPackRegistry(REGISTRY, TinkerShapeFamily.CODEC, TinkerShapeFamily.CODEC);
   }

   public static Optional<TinkerShapeFamily> find(RegistryAccess access, Block block) {
      Optional<TinkerShapeFamily> override = access.registry(REGISTRY).flatMap(registry -> registry.entrySet().stream()
         .filter(entry -> entry.getValue().contains(block)).sorted(ORDER).map(Map.Entry::getValue).findFirst());
      // A disabled override must also suppress the vanilla family.
      return override.or(() -> Optional.ofNullable(VanillaFamilies.BY_BLOCK.get(block))).filter(TinkerShapeFamily::enabled);
   }

   private static final class VanillaFamilies {
      private static final Map<Block, TinkerShapeFamily> BY_BLOCK = create();

      private static Map<Block, TinkerShapeFamily> create() {
         Map<Block, TinkerShapeFamily> result = new HashMap<>();
         BlockFamilies.getAllFamilies().forEach(family -> {
            Block stairs = family.get(BlockFamily.Variant.STAIRS);
            Block slab = family.get(BlockFamily.Variant.SLAB);
            if (stairs == null && slab == null) return;
            var shapes = new TinkerShapeFamily(family.getBaseBlock(), Optional.ofNullable(stairs), Optional.ofNullable(slab), 0, true);
            result.put(shapes.full(), shapes);
            if (stairs != null) result.put(stairs, shapes);
            if (slab != null) result.put(slab, shapes);
         });
         return Map.copyOf(result);
      }
   }
}
