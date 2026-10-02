package io.github.fastformer.fastplace.interaction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.fastformer.FastFormer;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/** Explicit wood families keep material changes separate from end-grain rotation. */
public record TinkerWoodFamily(Block log, Block stripped, Block bark, Optional<Block> strippedBark) {
   public static final ResourceKey<Registry<TinkerWoodFamily>> REGISTRY = ResourceKey.createRegistryKey(
      ResourceLocation.fromNamespaceAndPath(FastFormer.MOD_ID, "tinker_wood"));
   public static final Codec<TinkerWoodFamily> CODEC = RecordCodecBuilder.<TinkerWoodFamily>create(instance -> instance.group(
      BuiltInRegistries.BLOCK.byNameCodec().fieldOf("log").forGetter(TinkerWoodFamily::log),
      BuiltInRegistries.BLOCK.byNameCodec().fieldOf("stripped").forGetter(TinkerWoodFamily::stripped),
      BuiltInRegistries.BLOCK.byNameCodec().fieldOf("bark").forGetter(TinkerWoodFamily::bark),
      BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("stripped_bark").forGetter(TinkerWoodFamily::strippedBark)
   ).apply(instance, TinkerWoodFamily::new)).validate(family -> {
      var blocks = java.util.stream.Stream.concat(java.util.stream.Stream.of(family.log, family.stripped, family.bark), family.strippedBark.stream()).toList();
      if (blocks.stream().distinct().count() != blocks.size()
         || blocks.stream().anyMatch(block -> !block.defaultBlockState().hasProperty(BlockStateProperties.AXIS)
            || block.defaultBlockState().hasBlockEntity())) {
         return DataResult.error(() -> "Wood variants must be distinct blocks with an axis and no block entity");
      }
      return DataResult.success(family);
   });

   public static void register(DataPackRegistryEvent.NewRegistry event) {
      event.dataPackRegistry(REGISTRY, CODEC, CODEC);
   }

   public static Optional<TinkerWoodFamily> find(RegistryAccess access, Block block) {
      return access.registry(REGISTRY).flatMap(registry -> registry.entrySet().stream()
         .sorted(java.util.Comparator.comparing(entry -> entry.getKey().location().toString()))
         .map(java.util.Map.Entry::getValue).filter(family -> family.contains(block)).findFirst());
   }

   private boolean contains(Block block) {
      return block == log || block == stripped || block == bark || strippedBark.orElse(null) == block;
   }

   BlockState apply(BlockState state, BlockHitResult hit) {
      if ((state.is(log) || state.is(stripped)) && hit.getDirection().getAxis() == state.getValue(BlockStateProperties.AXIS)) {
         return state.cycle(BlockStateProperties.AXIS);
      }
      Block next = state.is(log) ? stripped : state.is(bark) ? log : bark;
      return next.withPropertiesOf(state);
   }
}
