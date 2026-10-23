package io.github.fastformer.fastplace.interaction;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.world.BlockEditTransaction;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TinkerPlanGameTests {
   private static final int FLAGS = PlacementUpdateMode.CLIENT_ONLY.flags();

   private TinkerPlanGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void boundedEditsConsumeLimitsAndUseAdjacentEdges(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (BlockState source : List.of(Blocks.SNOW.defaultBlockState(), Blocks.SEA_PICKLE.defaultBlockState(),
         Blocks.TURTLE_EGG.defaultBlockState())) {
         IntegerProperty property = source.getProperties().stream().filter(IntegerProperty.class::isInstance)
            .map(IntegerProperty.class::cast).filter(value -> !value.getName().equals("hatch")).findFirst().orElseThrow();
         int low = property.getPossibleValues().stream().mapToInt(Integer::intValue).min().orElseThrow();
         int high = property.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElseThrow();
         for (int value = low; value <= high; value++) {
            var state = source.setValue(property, value);
            helper.getLevel().setBlock(pos, state, FLAGS);
            assertState(helper, pos, click(pos, Direction.UP, 0.5, 1, 0.5), false,
               state.setValue(property, Math.min(high, value + 1)));
            assertState(helper, pos, click(pos, Direction.UP, 0.125, 1, 0.5), false,
               state.setValue(property, Math.max(low, value - 1)));
            assertState(helper, pos, click(pos, Direction.DOWN, 0.5, 0, 0.5), false, state);
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void cropAgesCycleFromEveryFaceWithoutChangingOtherProperties(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (Block block : List.of(Blocks.WHEAT, Blocks.BEETROOTS, Blocks.COCOA, Blocks.SWEET_BERRY_BUSH,
         Blocks.TORCHFLOWER_CROP, Blocks.PITCHER_CROP)) {
         var source = block.defaultBlockState();
         var age = (IntegerProperty) source.getProperties().stream().filter(property -> property.getName().equals("age"))
            .findFirst().orElseThrow();
         for (int value : age.getPossibleValues()) {
            var state = source.setValue(age, value);
            helper.getLevel().setBlock(pos, state, FLAGS);
            for (Direction face : Direction.values()) for (boolean shift : List.of(false, true)) {
               assertState(helper, pos, click(pos, face, 0.5, 0.5, 0.5), shift, state.cycle(age));
            }
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void repeatedCropModelsAdvanceToTheNextVisibleStage(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      Map<Block, List<Integer>> expectedByAge = Map.of(
         Blocks.CARROTS, List.of(2, 2, 4, 4, 7, 7, 7, 0),
         Blocks.POTATOES, List.of(2, 2, 4, 4, 7, 7, 7, 0),
         Blocks.NETHER_WART, List.of(1, 3, 3, 0));
      for (var entry : expectedByAge.entrySet()) {
         var source = entry.getKey().defaultBlockState();
         var age = (IntegerProperty) source.getBlock().getStateDefinition().getProperty("age");
         for (int value : age.getPossibleValues()) {
            var state = source.setValue(age, value);
            helper.getLevel().setBlock(pos, state, FLAGS);
            for (Direction face : Direction.values()) for (boolean shift : List.of(false, true)) {
               assertState(helper, pos, click(pos, face, 0.5, 0.5, 0.5), shift,
                  state.setValue(age, entry.getValue().get(value)));
               var hit = click(pos, face, 0.5, 0.5, 0.5);
               helper.assertTrue(BlockTinker.resolveAction(helper.getLevel(), hit, shift, false).changes().get(pos)
                  .equals(state.cycle(age)), "disabled stage skipping did not advance one age");
            }
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void fruitStemsGrowAttachAndReturnToMaturity(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (var pair : List.of(List.of(Blocks.PUMPKIN_STEM, Blocks.ATTACHED_PUMPKIN_STEM),
         List.of(Blocks.MELON_STEM, Blocks.ATTACHED_MELON_STEM))) {
         var mature = pair.getFirst().defaultBlockState().setValue(BlockStateProperties.AGE_7, 7);
         for (int age = 0; age <= 7; age++) {
            var state = mature.setValue(BlockStateProperties.AGE_7, age);
            helper.getLevel().setBlock(pos, state, FLAGS);
            for (Direction face : Direction.values()) {
               BlockState expected = age < 7 ? state.setValue(BlockStateProperties.AGE_7, age + 1)
                  : face == Direction.UP ? state.setValue(BlockStateProperties.AGE_7, 0)
                  : face.getAxis().isHorizontal() ? pair.getLast().defaultBlockState()
                     .setValue(BlockStateProperties.HORIZONTAL_FACING, face) : state;
               for (boolean shift : List.of(false, true)) {
                  assertState(helper, pos, click(pos, face, 0.5, 0.5, 0.5), shift, expected);
               }
            }
         }
         for (Direction facing : Direction.Plane.HORIZONTAL) {
            var attached = pair.getLast().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
            helper.getLevel().setBlock(pos, attached, FLAGS);
            for (Direction face : Direction.values()) {
               assertState(helper, pos, click(pos, face, 0.5, 0.5, 0.5), false, mature);
            }
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void composterFinalStageEmptiesWithoutProducingItems(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var full = Blocks.COMPOSTER.defaultBlockState().setValue(BlockStateProperties.LEVEL_COMPOSTER, 8);
      helper.getLevel().setBlock(pos, full, FLAGS);
      for (Direction face : Direction.values()) {
         assertState(helper, pos, click(pos, face, 0.5, 0.5, 0.5), false, Blocks.COMPOSTER.defaultBlockState());
      }
      for (int value = 0; value < 8; value++) {
         var state = full.setValue(BlockStateProperties.LEVEL_COMPOSTER, value);
         helper.getLevel().setBlock(pos, state, FLAGS);
         assertState(helper, pos, click(pos, Direction.UP, 0.5, 1, 0.5), false,
            state.setValue(BlockStateProperties.LEVEL_COMPOSTER, value + 1));
         assertState(helper, pos, click(pos, Direction.NORTH, 0.5, 0.5, 0), false,
            state.setValue(BlockStateProperties.LEVEL_COMPOSTER, Math.max(0, value - 1)));
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void candleCyclePreservesWaterAndVisitsAllEightStates(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (boolean wet : new boolean[] {false, true}) {
         for (int count = 1; count <= 4; count++) for (boolean lit : new boolean[] {false, true}) {
            if (wet && lit) continue;
            var state = Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, count)
               .setValue(BlockStateProperties.LIT, lit).setValue(BlockStateProperties.WATERLOGGED, wet);
            var expected = !wet && !lit ? state.setValue(BlockStateProperties.LIT, true)
               : state.setValue(BlockStateProperties.CANDLES, count % 4 + 1).setValue(BlockStateProperties.LIT, false);
            helper.getLevel().setBlock(pos, state, FLAGS);
            assertState(helper, pos, click(pos, Direction.UP, 0.5, 0.5, 0.5), false, expected);
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void explicitCyclesPreserveOtherPropertiesAndBlockVanillaActions(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (Block block : List.of(Blocks.CAKE, Blocks.PINK_PETALS, Blocks.SWEET_BERRY_BUSH, Blocks.SNIFFER_EGG,
         Blocks.WATER_CAULDRON, Blocks.POWDER_SNOW_CAULDRON, Blocks.BEEHIVE, Blocks.RESPAWN_ANCHOR,
         Blocks.LIGHT, Blocks.TORCHFLOWER_CROP, Blocks.PITCHER_CROP)) {
         var state = block.defaultBlockState();
         var property = state.getProperties().stream().filter(IntegerProperty.class::isInstance)
            .map(IntegerProperty.class::cast).findFirst().orElseThrow();
         for (int value : property.getPossibleValues()) {
            state = state.setValue(property, value);
            helper.getLevel().setBlock(pos, state, FLAGS);
            assertState(helper, pos, click(pos, Direction.UP, 0.5, 1, 0.5), false, state.cycle(property));
         }
      }
      for (Block block : List.of(Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT)) {
         var state = block.defaultBlockState();
         if (state.hasProperty(BlockStateProperties.AGE_25)) state = state.setValue(BlockStateProperties.AGE_25, 17);
         helper.getLevel().setBlock(pos, state, FLAGS);
         assertState(helper, pos, click(pos, Direction.SOUTH, 0.5, 0.5, 1), false, state.cycle(BlockStateProperties.BERRIES));
      }
      for (Block block : List.of(Blocks.CAULDRON, Blocks.LAVA_CAULDRON)) {
         var state = block.defaultBlockState();
         helper.getLevel().setBlock(pos, state, FLAGS);
         assertState(helper, pos, click(pos, Direction.UP, 0.5, 1, 0.5), false, state);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void amethystAndDripstonePreserveDirectionAndWater(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var amethyst = List.of(Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER);
      for (Direction facing : Direction.values()) for (int index = 0; index < amethyst.size(); index++) {
         var state = amethyst.get(index).defaultBlockState().setValue(BlockStateProperties.FACING, facing)
            .setValue(BlockStateProperties.WATERLOGGED, true);
         helper.getLevel().setBlock(pos, state, FLAGS);
         assertState(helper, pos, click(pos, Direction.UP, 0.5, 1, 0.5), false,
            amethyst.get((index + 1) % amethyst.size()).withPropertiesOf(state));
      }
      var thickness = List.of(DripstoneThickness.TIP, DripstoneThickness.TIP_MERGE, DripstoneThickness.FRUSTUM,
         DripstoneThickness.MIDDLE, DripstoneThickness.BASE);
      for (Direction vertical : List.of(Direction.UP, Direction.DOWN)) for (int index = 0; index < thickness.size(); index++) {
         var state = Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(BlockStateProperties.VERTICAL_DIRECTION, vertical)
            .setValue(BlockStateProperties.DRIPSTONE_THICKNESS, thickness.get(index)).setValue(BlockStateProperties.WATERLOGGED, true);
         helper.getLevel().setBlock(pos, state, FLAGS);
         assertState(helper, pos, click(pos, Direction.NORTH, 0.5, 0.5, 0), false,
            state.setValue(BlockStateProperties.DRIPSTONE_THICKNESS, thickness.get((index + 1) % thickness.size())));
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void copperMaterialCyclesPreserveShapeAndDoorHalves(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (Block initial : List.of(Blocks.CUT_COPPER, Blocks.CUT_COPPER_SLAB, Blocks.CUT_COPPER_STAIRS,
         Blocks.COPPER_DOOR, Blocks.COPPER_TRAPDOOR, Blocks.COPPER_GRATE, Blocks.COPPER_BULB)) {
         var original = initial.defaultBlockState();
         if (original.hasProperty(BlockStateProperties.WATERLOGGED)) original = original.setValue(BlockStateProperties.WATERLOGGED, true);
         BlockState current = original;
         for (int step = 0; step < 8; step++) {
            level.setBlock(pos, current, FLAGS);
            if (current.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
               level.setBlock(pos.above(), current.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), FLAGS);
            }
            var action = BlockTinker.resolveAction(level, click(pos, Direction.UP, 0.5, 1, 0.5), true);
            var target = action.changes().get(pos);
            helper.assertTrue(action.consumed() && target != null, "copper Shift cycle was not handled");
            for (var property : current.getProperties()) {
               helper.assertTrue(current.getValue(property).equals(target.getValue(property)), "copper cycle lost " + property.getName());
            }
            if (current.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
               var upper = action.changes().get(pos.above());
               helper.assertTrue(upper != null && upper.is(target.getBlock())
                  && upper.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER, "copper door halves diverged");
            }
            boolean targetWaxed = net.neoforged.neoforge.common.DataMapHooks.getBlockUnwaxed(target.getBlock()) != null;
            helper.assertTrue(targetWaxed == (step >= 3 && step < 7), "copper wax cycle used the wrong order");
            current = target;
         }
         helper.assertTrue(current.equals(original), "copper cycle did not wrap to its initial state");
      }
      var full = Blocks.COPPER_BLOCK.defaultBlockState();
      level.setBlock(pos, full, FLAGS);
      assertState(helper, pos, click(pos, Direction.UP, 0.5, 1, 0.5), false, Blocks.WAXED_COPPER_BLOCK.defaultBlockState());
      assertState(helper, pos, click(pos, Direction.SOUTH, 0.5, 0.5, 1), false, Blocks.EXPOSED_COPPER.defaultBlockState());
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void thinPlatesUseAttachmentPositionsInsteadOfHitNormals(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (Block block : List.of(Blocks.GLOW_LICHEN, Blocks.SCULK_VEIN, Blocks.VINE)) {
         var state = block.defaultBlockState().setValue(BlockStateProperties.WEST, true).setValue(BlockStateProperties.NORTH, true);
         level.setBlock(pos, state, FLAGS);
         Vec3 start = Vec3.atLowerCornerOf(pos).add(0.5, 0.5, 0.5);
         var hit = state.getShape(level, pos).clip(start, start.add(-1, 0, 0), pos);
         helper.assertTrue(hit != null && hit.getBlockPos().equals(pos) && hit.getDirection() == Direction.EAST,
            "ray did not hit the inner surface of the west plate");
         assertState(helper, pos, hit, false, state.setValue(BlockStateProperties.WEST, false));
         var single = state.setValue(BlockStateProperties.NORTH, false);
         level.setBlock(pos, single, FLAGS);
         assertState(helper, pos, hit, false, single);
         assertState(helper, pos, click(pos, Direction.EAST, 0.0625, 0.5, 0.125), false,
            single.setValue(BlockStateProperties.NORTH, true));
         assertState(helper, pos, click(pos, Direction.EAST, 0.0625, 0.5, 0.12501), false, single);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void containersKeepVanillaEntryAndShiftEditsKeepInventory(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (Block block : List.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER)) {
         var state = block.defaultBlockState();
         level.setBlock(pos, state, FLAGS);
         helper.assertTrue(!BlockTinker.resolveAction(level, click(pos, Direction.EAST, 1, 0.5, 0.5), false).consumed(), "furnace side blocked its UI");
         assertState(helper, pos, click(pos, Direction.EAST, 1, 0.5, 0.5), true,
            state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
         assertState(helper, pos, click(pos, Direction.NORTH, 0.5, 0.5, 0), false, state.cycle(BlockStateProperties.LIT));
      }
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 5000);
      var state = Blocks.CHEST.defaultBlockState();
      level.setBlock(pos, state, FLAGS);
      level.setBlock(pos.east(), state, FLAGS);
      ((ChestBlockEntity) level.getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 3));
      ((ChestBlockEntity) level.getBlockEntity(pos.east())).setItem(0, new ItemStack(Items.EMERALD, 5));
      helper.assertTrue(!BlockTinker.resolveAction(level, click(pos, Direction.EAST, 1, 0.5, 0.5), false).consumed(), "chest blocked UI");
      var joined = BlockTinker.resolveAction(level, click(pos, Direction.EAST, 1, 0.5, 0.5), true);
      helper.assertTrue(joined.changes().size() == 2, "chest join did not include both halves");
      helper.succeedWhen(() -> {
      helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(player.getServer(), level.dimension()), "waiting for world writer");
      helper.assertFalse(io.github.fastformer.server.input.ServerInputDispatcher.interactionBlocked(player), "waiting for history");
      helper.assertTrue(BlockTinker.tinker(player, level, joined.changes(), FLAGS) == BlockEditTransaction.Outcome.APPLIED, "chest join failed");
      helper.assertTrue(((ChestBlockEntity) level.getBlockEntity(pos)).getItem(0).getCount() == 3
         && ((ChestBlockEntity) level.getBlockEntity(pos.east())).getItem(0).getCount() == 5, "chest edit lost inventory");
      var split = BlockTinker.resolveAction(level, click(pos, Direction.UP, 0.5, 1, 0.5), true);
      helper.assertTrue(split.changes().size() == 2 && split.changes().values().stream()
         .allMatch(value -> value.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE), "chest split was not coordinated");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker", timeoutTicks = 1200)
   public static void pistonEditsAreBlockedByObstaclesAndUndoBothCells(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var initial = Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST);
      level.setBlock(pos, initial, FLAGS);
      level.setBlock(pos.east(), Blocks.STONE.defaultBlockState(), FLAGS);
      var hit = click(pos, Direction.NORTH, 0.5, 0.5, 0);
      var blocked = BlockTinker.resolveAction(level, hit, false);
      helper.assertTrue(blocked.consumed() && blocked.changes().isEmpty(), "piston replaced its obstruction");
      level.setBlock(pos.east(), Blocks.AIR.defaultBlockState(), FLAGS);
      var action = BlockTinker.resolveAction(level, hit, false);
      helper.assertTrue(action.changes().size() == 2 && action.changes().get(pos.east()).getValue(BlockStateProperties.PISTON_TYPE)
         == PistonType.STICKY, "piston head did not match its base");
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 5000);
      helper.assertTrue(BlockTinker.tinker(player, level, action.changes(), FLAGS) == BlockEditTransaction.Outcome.APPLIED, "piston transaction failed");
      var retract = BlockTinker.resolveAction(level, hit, false);
      helper.assertTrue(retract.changes().get(pos).equals(initial) && retract.changes().get(pos.east()).isAir(), "piston retraction changed direction or left its head");
      helper.assertTrue(retract.changes().keySet().iterator().next().equals(pos), "piston write order did not put the base first");
      helper.assertTrue(WorldHistoryManager.requestUndo(player, 1), "piston undo refused");
      helper.succeedWhen(() -> {
         try { Thread.sleep(5); } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
         }
         helper.assertTrue(!WorldHistoryManager.busy(player), "waiting for history");
         helper.assertTrue(level.getBlockState(pos).equals(initial) && level.getBlockState(pos.east()).isAir(), "undo did not restore both piston cells");
         helper.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            new net.minecraft.world.phys.AABB(pos).inflate(2)).isEmpty(), "piston undo created extra drops");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void controlledHeadRemovalDoesNotDestroyItsBase(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var base = Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.EXTENDED, true)
         .setValue(BlockStateProperties.FACING, Direction.EAST);
      var head = Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST);
      level.setBlock(pos, base, FLAGS);
      level.setBlock(pos.east(), head, FLAGS);
      helper.assertTrue(io.github.fastformer.fastplace.world.WorldWriteSideEffectGuard.setBlock(level, pos.east(), Blocks.AIR.defaultBlockState(), FLAGS),
         "controlled head removal did not write");
      helper.assertTrue(level.getBlockState(pos).equals(base), "head callback destroyed a cell outside its write");
      helper.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
         new net.minecraft.world.phys.AABB(pos).inflate(2)).isEmpty(), "head callback created drops");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void propertyDataPacksValidateProtectedPropertiesAndResolvePriority(GameTestHelper helper) {
      var rules = new MappedRegistry<>(TinkerPropertyRule.REGISTRY, Lifecycle.stable());
      var rule = TinkerPropertyRule.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
         "{\"blocks\":[\"minecraft:cake\"],\"property\":\"bites\",\"operation\":\"step_by_face\",\"priority\":10}")).getOrThrow();
      Registry.register(rules, ResourceLocation.fromNamespaceAndPath(FastFormer.MOD_ID, "cake"), rule);
      var access = new RegistryAccess.ImmutableRegistryAccess(List.of(rules));
      helper.assertTrue(TinkerPropertyRule.find(access, Blocks.CAKE, false).orElseThrow().equals(rule), "property data pack was ignored");
      for (String json : List.of(
         "{\"blocks\":[\"minecraft:oak_stairs\"],\"property\":\"waterlogged\"}",
         "{\"blocks\":[\"minecraft:dispenser\"],\"property\":\"facing\"}",
         "{\"blocks\":[\"minecraft:cake\"],\"property\":\"age\"}",
         "{\"blocks\":[\"minecraft:candle\"],\"property\":\"lit\",\"operation\":\"step_by_face\"}")) {
         helper.assertTrue(TinkerPropertyRule.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).error().isPresent(), "invalid property rule was accepted");
      }
      helper.succeed();
   }

   private static void assertState(GameTestHelper helper, BlockPos pos, BlockHitResult hit, boolean shift, BlockState expected) {
      var action = BlockTinker.resolveAction(helper.getLevel(), hit, shift);
      var before = helper.getLevel().getBlockState(pos);
      helper.assertTrue(action.consumed(), "edit unexpectedly passed to vanilla: " + before);
      helper.assertTrue(action.changes().size() == (before.equals(expected) ? 0 : 1), "single-cell edit wrote extra positions");
      helper.assertTrue(expected.equals(action.changes().getOrDefault(pos, before)), "wrong edit for " + before + ": " + action.changes());
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void mountingChangesPreserveDirectionAndOrdinaryInteraction(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (Direction facing : Direction.Plane.HORIZONTAL) {
         var grinder = Blocks.GRINDSTONE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
         var bell = Blocks.BELL.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
         for (var state : List.of(grinder, bell)) {
            helper.getLevel().setBlock(pos, state, FLAGS);
            helper.assertTrue(!BlockTinker.resolveAction(helper.getLevel(), click(pos, Direction.UP, 0.5, 1, 0.5), false).consumed(),
               "mounting rule intercepted ordinary interaction");
            var ceiling = state.hasProperty(BlockStateProperties.ATTACH_FACE)
               ? state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.CEILING)
               : state.setValue(BlockStateProperties.BELL_ATTACHMENT, BellAttachType.CEILING);
            assertState(helper, pos, click(pos, Direction.UP, 0.5, 1, 0.5), true, ceiling);
         }
         helper.getLevel().setBlock(pos, bell.setValue(BlockStateProperties.BELL_ATTACHMENT, BellAttachType.SINGLE_WALL), FLAGS);
         Direction opposite = facing.getOpposite();
         Vec3 point = new Vec3(0.5, 0.5, 0.5).add(Vec3.atLowerCornerOf(opposite.getNormal()).scale(0.5));
         var hit = click(pos, opposite, point.x, point.y, point.z);
         var doubleWall = bell.setValue(BlockStateProperties.BELL_ATTACHMENT, BellAttachType.DOUBLE_WALL);
         assertState(helper, pos, hit, true, doubleWall);
         helper.getLevel().setBlock(pos, doubleWall, FLAGS);
         assertState(helper, pos, hit, true, bell.setValue(BlockStateProperties.BELL_ATTACHMENT, BellAttachType.SINGLE_WALL));
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void railEdgesAndWireEdgesModifyTheClickedConnection(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), FLAGS);
      var rail = Blocks.RAIL.defaultBlockState();
      level.setBlock(pos, rail, FLAGS);
      assertState(helper, pos, click(pos, Direction.UP, 0.5, 0.0625, 0.125), false,
         rail.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.ASCENDING_NORTH));
      assertState(helper, pos, click(pos, Direction.UP, 0.875, 0.0625, 0.25), false,
         rail.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.NORTH_EAST));
      assertState(helper, pos, click(pos, Direction.UP, 0.5, 0.0625, 0.5), false,
         rail.setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST));
      var wire = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(BlockStateProperties.WEST_REDSTONE, RedstoneSide.UP);
      level.setBlock(pos, wire, FLAGS);
      var spread = wire.setValue(BlockStateProperties.NORTH_REDSTONE, RedstoneSide.SIDE)
         .setValue(BlockStateProperties.SOUTH_REDSTONE, RedstoneSide.SIDE).setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.SIDE);
      assertState(helper, pos, click(pos, Direction.UP, 0.5, 0.0625, 0.5), false, spread);
      assertState(helper, pos, click(pos, Direction.UP, 0.875, 0.0625, 0.5), false,
         wire.setValue(BlockStateProperties.EAST_REDSTONE, RedstoneSide.SIDE));
      level.setBlock(pos, spread, FLAGS);
      assertState(helper, pos, click(pos, Direction.UP, 0.5, 0.0625, 0.5), false, wire);
      helper.succeed();
   }

   private static BlockHitResult click(BlockPos pos, Direction side, double x, double y, double z) {
      return new BlockHitResult(Vec3.atLowerCornerOf(pos).add(x, y, z), side, pos, false);
   }
}
