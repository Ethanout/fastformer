package io.github.fastformer.fastplace.selection;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Family tags are synchronized by vanilla datapack loading. Each match uses the original seed. */
public final class SmartSelectionSimilarity {
   private SmartSelectionSimilarity() { }

   public static Predicate<BlockState> fromSeed(BlockState seed, boolean family) {
      List<TagKey<Block>> tags = family ? seed.getTags().filter(tag ->
         tag.location().getNamespace().equals("fastformer")
            && tag.location().getPath().startsWith("selection_families/")).toList() : List.of();
      return candidate -> !candidate.isAir() && (candidate.is(seed.getBlock())
         || tags.stream().anyMatch(candidate::is));
   }
}
