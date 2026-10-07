package io.github.fastformer.fastplace.interaction;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.history.TinkerHistoryGroupingRules;
import io.github.fastformer.fastplace.history.TinkerHistoryGroupingRules.KeyPart;
import io.github.fastformer.fastplace.history.TinkerHistoryTags;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TinkerHistoryGroupingGameTests {
   private TinkerHistoryGroupingGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "tinker_history_grouping")
   public static void shapeEditsShareHalfAndMaterialAcrossFacesPropertiesAndTypes(GameTestHelper helper) {
      var stairs = Blocks.OAK_STAIRS.defaultBlockState();
      var slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
      var sequence = List.of(Blocks.OAK_PLANKS.defaultBlockState(),
         stairs.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.INNER_LEFT), stairs,
         stairs.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.OUTER_LEFT), slab);
      String expected = null;
      for (int index = 1; index < sequence.size(); index++) {
         String key = cluster(helper, sequence.get(index - 1), sequence.get(index),
            index % 2 == 0 ? Direction.NORTH : Direction.UP, TinkerHistoryGroupingRules.DEFAULTS);
         if (expected == null) expected = key;
         helper.assertTrue(key.equals(expected), "shape edit split by type, face or property set: " + key);
      }
      String add = cluster(helper, slab, stairs.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.OUTER_LEFT),
         Direction.UP, TinkerHistoryGroupingRules.DEFAULTS);
      helper.assertTrue(add.equals(expected), "adding and removing from the same half did not share the default group");
      var rotated = stairs.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
      helper.assertTrue(cluster(helper, stairs, rotated, Direction.WEST, TinkerHistoryGroupingRules.DEFAULTS).equals(expected),
         "shape and facing changes did not share the edited half");
      var top = stairs.setValue(BlockStateProperties.HALF, Half.TOP);
      helper.assertTrue(!cluster(helper, top.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.INNER_LEFT), top,
         Direction.DOWN, TinkerHistoryGroupingRules.DEFAULTS).equals(expected), "lower-half edit joined the upper half");
      helper.assertTrue(!cluster(helper, Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_STAIRS.defaultBlockState(),
         Direction.UP, TinkerHistoryGroupingRules.DEFAULTS).equals(expected), "different materials joined by default");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker_history_grouping")
   public static void configuredDimensionsControlShapeAndPropertyGrouping(GameTestHelper helper) {
      var full = Blocks.OAK_PLANKS.defaultBlockState();
      var inner = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.INNER_LEFT);
      var defaults = TinkerHistoryGroupingRules.DEFAULTS;
      var byFace = new TinkerHistoryGroupingRules(Set.of(KeyPart.MATERIAL, KeyPart.EDITED_HALF, KeyPart.FACE), defaults.generalParts(), Set.of());
      helper.assertTrue(!cluster(helper, full, inner, Direction.UP, byFace).equals(cluster(helper, full, inner, Direction.NORTH, byFace)),
         "face grouping option was ignored");
      var byDirection = new TinkerHistoryGroupingRules(Set.of(KeyPart.MATERIAL, KeyPart.EDITED_HALF, KeyPart.EDIT_DIRECTION), defaults.generalParts(), Set.of());
      helper.assertTrue(!cluster(helper, full, inner, Direction.UP, byDirection).equals(cluster(helper, inner, full, Direction.UP, byDirection)),
         "add/remove grouping option was ignored");
      var byHalf = new TinkerHistoryGroupingRules(Set.of(KeyPart.EDITED_HALF), defaults.generalParts(), Set.of());
      helper.assertTrue(cluster(helper, full, inner, Direction.UP, byHalf).equals(cluster(helper, Blocks.SPRUCE_PLANKS.defaultBlockState(),
         Blocks.SPRUCE_STAIRS.defaultBlockState(), Direction.NORTH, byHalf)), "material-free grouping still split materials");
      var ignored = new TinkerHistoryGroupingRules(defaults.shapeParts(), Set.of(KeyPart.PROPERTIES), Set.of("level"));
      var composter = Blocks.COMPOSTER.defaultBlockState();
      String key = cluster(helper, composter, composter.setValue(BlockStateProperties.LEVEL_COMPOSTER, 1), Direction.UP, ignored);
      helper.assertTrue(key.equals("general:"), "ignored property still contributed to grouping");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker_history_grouping")
   public static void blockTagsControlShapeMaterialsAndExclusions(GameTestHelper helper) {
      var defaults = TinkerHistoryGroupingRules.DEFAULTS;
      var full = Blocks.OAK_PLANKS.defaultBlockState();
      var stairs = Blocks.OAK_STAIRS.defaultBlockState();
      String key = cluster(helper, full, stairs, Direction.UP, defaults);
      helper.assertTrue(key.contains("material=#fastformer:selection_families/oak"), "shared material tag was not used");

      var shapeTags = new TinkerHistoryTags(TinkerHistoryTags.blockTag("minecraft:logs"),
         defaults.tags().noMerge(), defaults.tags().materialPrefixes());
      helper.assertTrue(cluster(helper, full, stairs, Direction.UP, withTags(shapeTags)).startsWith("general:"),
         "shape tag did not control eligibility");

      var materialTags = new TinkerHistoryTags(defaults.tags().shapeEdits(), defaults.tags().noMerge(), List.of("minecraft:stairs"));
      var inner = stairs.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.INNER_LEFT);
      var spruce = Blocks.SPRUCE_STAIRS.defaultBlockState();
      helper.assertTrue(cluster(helper, inner, stairs, Direction.UP, withTags(materialTags)).equals(
         cluster(helper, spruce.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.INNER_LEFT), spruce,
            Direction.UP, withTags(materialTags))), "configured shared material tag did not group blocks");

      var excluded = withTags(new TinkerHistoryTags(defaults.tags().shapeEdits(),
         TinkerHistoryTags.blockTag("minecraft:planks"), defaults.tags().materialPrefixes()));
      BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
      var hit = new BlockHitResult(position.getCenter(), Direction.UP, position, false);
      helper.getLevel().setBlock(position, full, io.github.fastformer.fastplace.placement.PlacementUpdateMode.CLIENT_ONLY.flags());
      helper.assertTrue(TinkerHistoryClassifier.cluster(helper.getLevel(), hit, Map.of(position, stairs), excluded).isEmpty(),
         "excluded before-state remained mergeable");
      helper.getLevel().setBlock(position, stairs, io.github.fastformer.fastplace.placement.PlacementUpdateMode.CLIENT_ONLY.flags());
      helper.assertTrue(TinkerHistoryClassifier.cluster(helper.getLevel(), hit, Map.of(position, full), excluded).isEmpty(),
         "excluded after-state remained mergeable");
      helper.assertTrue(TinkerHistoryClassifier.cluster(helper.getLevel(), hit,
         Map.of(position, inner, position.above(), full), excluded).isEmpty(), "secondary changed block bypassed exclusion");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker_history_grouping")
   public static void missingMaterialTagsUseDataDrivenShapeFamily(GameTestHelper helper) {
      var defaults = TinkerHistoryGroupingRules.DEFAULTS;
      var tags = new TinkerHistoryTags(defaults.tags().shapeEdits(), defaults.tags().noMerge(), List.of("fastformer:missing_material/"));
      String key = cluster(helper, Blocks.OAK_PLANKS.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState(),
         Direction.UP, withTags(tags));
      helper.assertTrue(key.contains("material=minecraft:oak_planks"), "shape family fallback lost its material");
      helper.succeed();
   }

   private static TinkerHistoryGroupingRules withTags(TinkerHistoryTags tags) {
      var defaults = TinkerHistoryGroupingRules.DEFAULTS;
      return new TinkerHistoryGroupingRules(defaults.shapeParts(), defaults.generalParts(), defaults.ignoredProperties(), tags);
   }

   private static String cluster(GameTestHelper helper, BlockState before, BlockState after, Direction face, TinkerHistoryGroupingRules rules) {
      BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlock(position, before, io.github.fastformer.fastplace.placement.PlacementUpdateMode.CLIENT_ONLY.flags());
      var hit = new BlockHitResult(position.getCenter().add(0, 0.5, 0), face, position, false);
      return TinkerHistoryClassifier.cluster(helper.getLevel(), hit, Map.of(position, after), rules).orElseThrow();
   }
}
