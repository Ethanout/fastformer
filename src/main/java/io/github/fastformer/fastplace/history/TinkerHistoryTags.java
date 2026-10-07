package io.github.fastformer.fastplace.history;

import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Data-pack tags select shape edits, material groups and records that stay independent. */
public record TinkerHistoryTags(TagKey<Block> shapeEdits, TagKey<Block> noMerge, List<String> materialPrefixes) {
   public static final TinkerHistoryTags DEFAULTS = new TinkerHistoryTags(
      blockTag("fastformer:history/shape_edit"), blockTag("fastformer:history/no_merge"),
      List.of("fastformer:selection_families/"));

   public TinkerHistoryTags {
      java.util.Objects.requireNonNull(shapeEdits);
      java.util.Objects.requireNonNull(noMerge);
      materialPrefixes = List.copyOf(materialPrefixes);
      if (materialPrefixes.stream().anyMatch(prefix -> ResourceLocation.tryParse(prefix) == null)) {
         throw new IllegalArgumentException("Invalid material tag prefix");
      }
   }

   public static TagKey<Block> blockTag(String name) {
      return TagKey.create(Registries.BLOCK, ResourceLocation.parse(name));
   }
}
