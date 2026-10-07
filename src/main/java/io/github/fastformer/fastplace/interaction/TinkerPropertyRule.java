package io.github.fastformer.fastplace.interaction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.fastformer.FastFormer;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/** Explicit single-cell property overrides; structure edits stay in their own rules. */
public record TinkerPropertyRule(List<Block> blocks, String property, String operation,
   Optional<Boolean> sneaking, int priority, boolean enabled) {
   public static final ResourceKey<Registry<TinkerPropertyRule>> REGISTRY = ResourceKey.createRegistryKey(
      ResourceLocation.fromNamespaceAndPath(FastFormer.MOD_ID, "tinker_property"));
   private static final Set<String> FORBIDDEN = Set.of("waterlogged", "facing", "axis", "rotation", "orientation",
      "half", "type", "extended", "has_book", "has_record", "has_bottle_0", "has_bottle_1", "has_bottle_2");
   public static final Codec<TinkerPropertyRule> CODEC = RecordCodecBuilder.<TinkerPropertyRule>create(instance -> instance.group(
      BuiltInRegistries.BLOCK.byNameCodec().listOf().fieldOf("blocks").forGetter(TinkerPropertyRule::blocks),
      Codec.STRING.fieldOf("property").forGetter(TinkerPropertyRule::property),
      Codec.STRING.optionalFieldOf("operation", "cycle").forGetter(TinkerPropertyRule::operation),
      Codec.BOOL.optionalFieldOf("sneaking").forGetter(TinkerPropertyRule::sneaking),
      Codec.INT.optionalFieldOf("priority", 0).forGetter(TinkerPropertyRule::priority),
      Codec.BOOL.optionalFieldOf("enabled", true).forGetter(TinkerPropertyRule::enabled)
   ).apply(instance, TinkerPropertyRule::new)).validate(TinkerPropertyRule::validate);

   public static void register(DataPackRegistryEvent.NewRegistry event) {
      event.dataPackRegistry(REGISTRY, CODEC, CODEC);
   }

   static Optional<TinkerPropertyRule> find(RegistryAccess access, Block block, boolean shift) {
      return access.registry(REGISTRY).flatMap(registry -> registry.entrySet().stream()
         .filter(entry -> entry.getValue().blocks.contains(block)
            && entry.getValue().sneaking.map(value -> value == shift).orElse(true))
         .sorted(Comparator.<java.util.Map.Entry<ResourceKey<TinkerPropertyRule>, TinkerPropertyRule>>
            comparingInt(entry -> entry.getValue().priority).reversed().thenComparing(entry -> entry.getKey().location().toString()))
         .map(java.util.Map.Entry::getValue).findFirst());
   }

   TinkerAction apply(Level level, BlockState state, BlockHitResult hit) {
      if (!enabled) return TinkerAction.NO_CHANGE;
      Property<?> target = state.getBlock().getStateDefinition().getProperty(property);
      if (target == null) return TinkerAction.NO_CHANGE;
      BlockState changed = switch (operation) {
         case "cycle" -> cycle(state, target);
         case "step_by_face" -> TinkerStateRules.adjust(state,
            (net.minecraft.world.level.block.state.properties.IntegerProperty) target, TinkerSurfaceHit.sideOperation(hit));
         default -> state;
      };
      if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
         && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)
         && changed.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
         changed = changed.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, false);
      }
      return TinkerAction.edit(level, hit.getBlockPos(), changed);
   }

   private static DataResult<TinkerPropertyRule> validate(TinkerPropertyRule rule) {
      if (rule.blocks.isEmpty() || FORBIDDEN.contains(rule.property) || rule.property.startsWith("slot_")) {
         return DataResult.error(() -> "Property rule cannot change structure, water or direction");
      }
      if (!Set.of("cycle", "step_by_face").contains(rule.operation)) return DataResult.error(() -> "Unknown property operation");
      for (Block block : rule.blocks) {
         Property<?> property = block.getStateDefinition().getProperty(rule.property);
         if (property == null) return DataResult.error(() -> "Block does not have property " + rule.property);
         if (rule.operation.equals("step_by_face") && !(property instanceof net.minecraft.world.level.block.state.properties.IntegerProperty)) {
            return DataResult.error(() -> "step_by_face requires an integer property");
         }
      }
      return DataResult.success(rule);
   }

   private static <T extends Comparable<T>> BlockState cycle(BlockState state, Property<T> property) {
      return state.cycle(property);
   }
}
