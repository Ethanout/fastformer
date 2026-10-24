package io.github.fastformer.fastplace.interaction;

import io.github.fastformer.fastplace.history.TinkerHistoryGroupingRules;
import io.github.fastformer.fastplace.history.TinkerHistoryGroupingRules.KeyPart;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Classifies completed edit intent, using the same material families and occupancy as the wrench. */
public final class TinkerHistoryClassifier {
   private TinkerHistoryClassifier() { }

   public static Optional<String> cluster(Level level, BlockHitResult hit, Map<BlockPos, BlockState> changes, TinkerHistoryGroupingRules rules) {
      return cluster(level, hit, changes, level::getBlockState, rules);
   }

   public static Optional<String> cluster(Level level, BlockHitResult hit, Map<BlockPos, BlockState> changes,
      java.util.function.Function<BlockPos, BlockState> source, TinkerHistoryGroupingRules rules) {
      if (changes.entrySet().stream().anyMatch(entry -> source.apply(entry.getKey()).is(rules.tags().noMerge())
         || entry.getValue().is(rules.tags().noMerge()))) return Optional.empty();
      BlockState before = source.apply(hit.getBlockPos());
      BlockState after = changes.get(hit.getBlockPos());
      if (changes.size() == 1 && after != null) {
         var shape = shapeCluster(level, hit, before, after, rules);
         if (shape.isPresent()) return shape;
      }
      return Optional.of(generalCluster(source, hit, changes, rules));
   }

   private static Optional<String> shapeCluster(Level level, BlockHitResult hit, BlockState before, BlockState after,
      TinkerHistoryGroupingRules rules) {
      if (before.hasBlockEntity() || after.hasBlockEntity()) return Optional.empty();
      if (!before.is(rules.tags().shapeEdits()) && !after.is(rules.tags().shapeEdits())) return Optional.empty();
      var material = materialGroup(level, before, after, rules);
      if (material.isEmpty()) return Optional.empty();
      int oldMask = TinkerShapeTransform.occupancy(before);
      int newMask = TinkerShapeTransform.occupancy(after);
      int changed = oldMask ^ newMask;
      if (changed == 0) return Optional.empty();
      TreeSet<String> parts = new TreeSet<>();
      if (rules.shapeParts().contains(KeyPart.MATERIAL)) parts.add("material=" + material.orElseThrow());
      if (rules.shapeParts().contains(KeyPart.EDITED_HALF)) parts.add("half=" + editedHalf(changed));
      if (rules.shapeParts().contains(KeyPart.EDIT_DIRECTION)) parts.add("direction=" + editDirection(oldMask, newMask));
      if (rules.shapeParts().contains(KeyPart.FACE)) parts.add("face=" + hit.getDirection().getName());
      return Optional.of("shape:" + String.join("|", parts));
   }

   private static Optional<String> materialGroup(Level level, BlockState before, BlockState after, TinkerHistoryGroupingRules rules) {
      var tag = before.getTags()
         .filter(candidate -> rules.tags().materialPrefixes().stream().anyMatch(prefix -> candidate.location().toString().startsWith(prefix)))
         .filter(after::is)
         .map(candidate -> "#" + candidate.location())
         .sorted().findFirst();
      if (tag.isPresent()) return tag;
      var family = TinkerShapeFamilies.find(level.registryAccess(), before.getBlock());
      if (family.isPresent() && family.orElseThrow().contains(after.getBlock())) {
         return Optional.of(id(family.orElseThrow().full()));
      }
      return before.getBlock() == after.getBlock() ? Optional.of(id(before.getBlock())) : Optional.empty();
   }

   private static String editedHalf(int changed) {
      if ((changed & 0x0F) == 0) return "upper";
      return (changed & 0xF0) == 0 ? "lower" : "both";
   }

   private static String editDirection(int before, int after) {
      if ((after & ~before) == 0) return "remove";
      return (before & ~after) == 0 ? "add" : "mixed";
   }

   private static String generalCluster(java.util.function.Function<BlockPos, BlockState> source, BlockHitResult hit, Map<BlockPos, BlockState> changes,
      TinkerHistoryGroupingRules rules) {
      TreeSet<String> parts = new TreeSet<>();
      if (rules.generalParts().contains(KeyPart.FACE)) parts.add("face=" + hit.getDirection().getName());
      for (var entry : changes.entrySet()) {
         BlockState before = source.apply(entry.getKey());
         BlockState after = entry.getValue();
         if (rules.generalParts().contains(KeyPart.BLOCK_TYPE)) parts.add("block=" + id(before.getBlock()));
         if (before.getBlock() != after.getBlock()) {
            if (rules.generalParts().contains(KeyPart.CONVERSION)) parts.add("conversion=" + id(before.getBlock()) + ">" + id(after.getBlock()));
         } else if (rules.generalParts().contains(KeyPart.PROPERTIES)) {
            for (var property : before.getProperties()) {
               if (!rules.ignoredProperties().contains(property.getName()) && !before.getValue(property).equals(after.getValue(property))) {
                  parts.add("property=" + property.getName());
               }
            }
         }
      }
      return "general:" + String.join("|", parts);
   }

   private static String id(Block block) { return BuiltInRegistries.BLOCK.getKey(block).toString(); }
}
