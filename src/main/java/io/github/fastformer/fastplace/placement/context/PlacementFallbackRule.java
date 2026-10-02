package io.github.fastformer.fastplace.placement.context;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public record PlacementFallbackRule(
   TagKey<Block> blockTag, int priority, boolean enabled, Map<String, PlacementFallbackValue> properties
) {
   public static final Codec<PlacementFallbackRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      TagKey.codec(Registries.BLOCK).fieldOf("block_tag").forGetter(PlacementFallbackRule::blockTag),
      Codec.INT.optionalFieldOf("priority", 0).forGetter(PlacementFallbackRule::priority),
      Codec.BOOL.optionalFieldOf("enabled", true).forGetter(PlacementFallbackRule::enabled),
      Codec.unboundedMap(Codec.STRING, PlacementFallbackValue.CODEC).optionalFieldOf("properties", Map.of())
         .forGetter(PlacementFallbackRule::properties)
   ).apply(instance, PlacementFallbackRule::new));

   public PlacementFallbackRule {
      properties = Map.copyOf(properties);
   }

   public Optional<BlockState> apply(BlockState original, BlockPlaceContext context) {
      if (!enabled || !original.is(blockTag)) return Optional.empty();
      BlockState state = original;
      for (var assignment : properties.entrySet()) {
         Property<?> property = state.getBlock().getStateDefinition().getProperty(assignment.getKey());
         if (property == null) return Optional.empty();
         var updated = setValue(state, property, assignment.getValue().resolve(context));
         // A tag can contain blocks with different properties. Apply a rule only as a whole.
         if (updated.isEmpty()) return Optional.empty();
         state = updated.orElseThrow();
      }
      return Optional.of(state);
   }

   private static <T extends Comparable<T>> Optional<BlockState> setValue(BlockState state, Property<T> property, String value) {
      return property.getValue(value).map(parsed -> state.setValue(property, parsed));
   }
}
