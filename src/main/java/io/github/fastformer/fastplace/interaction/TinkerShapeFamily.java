package io.github.fastformer.fastplace.interaction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;

public record TinkerShapeFamily(Block full, Optional<Block> stairs, Optional<Block> slab, int priority, boolean enabled) {
   public static final Codec<TinkerShapeFamily> CODEC = RecordCodecBuilder.<TinkerShapeFamily>create(instance -> instance.group(
      BuiltInRegistries.BLOCK.byNameCodec().fieldOf("full").forGetter(TinkerShapeFamily::full),
      BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("stairs").forGetter(TinkerShapeFamily::stairs),
      BuiltInRegistries.BLOCK.byNameCodec().optionalFieldOf("slab").forGetter(TinkerShapeFamily::slab),
      Codec.INT.optionalFieldOf("priority", 0).forGetter(TinkerShapeFamily::priority),
      Codec.BOOL.optionalFieldOf("enabled", true).forGetter(TinkerShapeFamily::enabled)
   ).apply(instance, TinkerShapeFamily::new)).validate(TinkerShapeFamily::validate);

   public boolean contains(Block block) {
      return full == block || stairs.orElse(null) == block || slab.orElse(null) == block;
   }

   private static DataResult<TinkerShapeFamily> validate(TinkerShapeFamily family) {
      if (family.full.defaultBlockState().isAir() || family.full instanceof SlabBlock || family.full instanceof StairBlock
         || family.full.defaultBlockState().hasBlockEntity()) {
         return DataResult.error(() -> "The full block must not be air, a slab, stairs, or a block entity");
      }
      if (family.stairs.isEmpty() && family.slab.isEmpty()) {
         return DataResult.error(() -> "A tinker family needs stairs or a slab");
      }
      if (family.stairs.filter(block -> !(block instanceof StairBlock) || block.defaultBlockState().hasBlockEntity()).isPresent()
         || family.slab.filter(block -> !(block instanceof SlabBlock) || block.defaultBlockState().hasBlockEntity()).isPresent()) {
         return DataResult.error(() -> "Tinker variants must be stairs or slabs without block entities");
      }
      return DataResult.success(family);
   }
}
