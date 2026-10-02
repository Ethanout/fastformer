package io.github.fastformer.fastplace.interaction;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.world.ShortWriteTransaction;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlockTinkerGameTests {
   private static final int FLAGS = PlacementUpdateMode.CLIENT_ONLY.flags();

   private BlockTinkerGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void shapeConversionsKeepMaterialAndFollowClickedPart(GameTestHelper helper) {
      for (Block full : List.of(Blocks.OAK_PLANKS, Blocks.STONE, Blocks.QUARTZ_BLOCK, Blocks.WAXED_OXIDIZED_CUT_COPPER)) {
         var family = TinkerShapeFamilies.find(helper.getLevel().registryAccess(), full).orElseThrow();
         for (Direction face : Direction.values()) {
            for (int cell = 0; cell < 8; cell++) {
               double x = (cell & 1) == 0 ? 0.25 : 0.75;
               double z = (cell & 2) == 0 ? 0.25 : 0.75;
               double y = (cell & 4) == 0 ? 0.25 : 0.75;
               double coordinate = face.getAxis().choose(x, y, z);
               if ((coordinate > 0.5) != (face.getAxisDirection() == Direction.AxisDirection.POSITIVE)) continue;
               Vec3 center = new Vec3(x, y, z);
               Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
               var outerHit = new BlockHitResult(center.add(normal.scale(0.25)), face, BlockPos.ZERO, false);
               BlockState stairs = TinkerShapeTransform.apply(family, full.defaultBlockState(), outerHit);
               helper.assertTrue(stairs.is(family.stairs().orElseThrow()), "full block lost its material family");
               helper.assertTrue(TinkerShapeTransform.occupancy(stairs) == (255 & ~(1 << cell)), "click changed more than its corner");
               var innerHit = new BlockHitResult(center.subtract(normal.scale(0.25)), face, BlockPos.ZERO, false);
               helper.assertTrue(TinkerShapeTransform.apply(family, stairs, innerHit).is(full), "recess click did not fill the same corner");
            }
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void partialShapesRetainWaterAndUnrelatedBlocksKeepTheirBehavior(GameTestHelper helper) {
      var family = TinkerShapeFamilies.find(helper.getLevel().registryAccess(), Blocks.OAK_PLANKS).orElseThrow();
      var wetSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
      var stairs = TinkerShapeTransform.apply(family, wetSlab, hitAt(BlockPos.ZERO, Direction.UP, 0.25, 0.5, 0.25));
      helper.assertTrue(stairs.getValue(BlockStateProperties.WATERLOGGED), "slab to stairs lost water");
      var slab = TinkerShapeTransform.apply(family, stairs, hitAt(BlockPos.ZERO, Direction.UP, 0.25, 1, 0.25));
      helper.assertTrue(slab.getValue(BlockStateProperties.WATERLOGGED), "stairs to slab lost water");
      var cutSandstone = TinkerShapeFamilies.find(helper.getLevel().registryAccess(), Blocks.CUT_SANDSTONE).orElseThrow();
      helper.assertTrue(TinkerShapeTransform.apply(cutSandstone, Blocks.CUT_SANDSTONE.defaultBlockState(), hit(Direction.SOUTH, 0.75))
         .is(Blocks.CUT_SANDSTONE_SLAB), "slab-only family did not convert");
      var doubleSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
      helper.assertTrue(TinkerShapeTransform.apply(family, doubleSlab, hitAt(BlockPos.ZERO, Direction.SOUTH, 0.25, 0.75, 1)).is(Blocks.OAK_STAIRS),
         "double slab did not behave as a full block");
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var fence = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.WEST, true);
      helper.getLevel().setBlock(pos, fence, FLAGS);
      var armHit = new BlockHitResult(Vec3.atLowerCornerOf(pos).add(0.2, 0.6, 0.625), Direction.SOUTH, pos, false);
      helper.assertTrue(BlockTinker.resolve(helper.getLevel(), armHit).get(pos).equals(fence.setValue(BlockStateProperties.WEST, false)),
         "fence click changed the face instead of the west arm");
      helper.getLevel().setBlock(pos, Blocks.IRON_TRAPDOOR.defaultBlockState(), FLAGS);
      var trapdoor = BlockTinker.resolve(helper.getLevel(), new BlockHitResult(pos.getCenter(), Direction.UP, pos, false)).get(pos);
      helper.assertTrue(trapdoor.getValue(BlockStateProperties.OPEN), "iron trapdoor did not open");
      helper.assertTrue(TinkerShapeFamilies.find(helper.getLevel().registryAccess(), Blocks.CHEST).isEmpty(), "container acquired a shape conversion");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void dataPacksCanOverrideAndDisableShapeFamilies(GameTestHelper helper) {
      var rules = new MappedRegistry<>(TinkerShapeFamilies.REGISTRY, Lifecycle.stable());
      String json = "{\"full\":\"minecraft:stone\",\"stairs\":\"minecraft:oak_stairs\",\"slab\":\"minecraft:oak_slab\",\"priority\":10}";
      var rule = TinkerShapeFamily.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
      var encoded = TinkerShapeFamily.CODEC.encodeStart(JsonOps.INSTANCE, rule).getOrThrow();
      Registry.register(rules, ResourceLocation.fromNamespaceAndPath(FastFormer.MOD_ID, "override"),
         TinkerShapeFamily.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
      var access = new RegistryAccess.ImmutableRegistryAccess(List.of(rules));
      helper.assertTrue(TinkerShapeFamilies.find(access, Blocks.STONE).orElseThrow().stairs().orElseThrow() == Blocks.OAK_STAIRS,
         "data pack did not override the vanilla family");
      Registry.register(rules, ResourceLocation.fromNamespaceAndPath(FastFormer.MOD_ID, "disabled"),
         new TinkerShapeFamily(Blocks.STONE, Optional.of(Blocks.STONE_STAIRS), Optional.of(Blocks.STONE_SLAB), 20, false));
      helper.assertTrue(TinkerShapeFamilies.find(access, Blocks.STONE).isEmpty(), "disabled family fell back to vanilla");
      helper.assertTrue(TinkerShapeFamily.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
         "{\"full\":\"minecraft:chest\",\"slab\":\"minecraft:stone_slab\"}")).error().isPresent(), "container family was accepted");
      helper.assertTrue(TinkerShapeFamily.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
         "{\"full\":\"minecraft:stone\",\"stairs\":\"minecraft:dirt\"}")).error().isPresent(), "invalid stairs were accepted");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker", timeoutTicks = 1200)
   public static void typeChangesSupportUndoAndRedo(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 5000);
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var original = Blocks.OAK_PLANKS.defaultBlockState();
      level.setBlock(pos, original, FLAGS);
      var target = new BlockHitResult(Vec3.atLowerCornerOf(pos).add(0.5, 0.75, 1), Direction.SOUTH, pos, false);
      Map<BlockPos, BlockState> changes = BlockTinker.resolve(level, target);
      helper.assertTrue(changes.get(pos).is(Blocks.OAK_STAIRS), "resolver did not change block type");
      helper.assertTrue(BlockTinker.tinker(player, level, changes, FLAGS) == ShortWriteTransaction.Outcome.APPLIED, "tinker write failed");
      helper.assertTrue(WorldHistoryManager.requestUndo(player, 1), "undo request failed");
      boolean[] redo = {false};
      helper.succeedWhen(() -> {
         // The test server runs ticks without pacing. Allow the history journal to reach disk.
         try {
            Thread.sleep(5);
         } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
         }
         helper.assertTrue(!WorldHistoryManager.busy(player), "waiting for history");
         if (!redo[0]) {
            helper.assertTrue(level.getBlockState(pos).equals(original), "undo did not restore the full block");
            helper.assertTrue(WorldHistoryManager.requestRedo(player, 1), "redo request failed");
            redo[0] = true;
            helper.fail("waiting for redo");
         }
         helper.assertTrue(level.getBlockState(pos).equals(changes.get(pos)), "redo did not restore stairs");
      });
   }

   private static BlockHitResult hit(Direction face, double y) {
      return new BlockHitResult(new Vec3(0.5, y, 0.5), face, BlockPos.ZERO, false);
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void woodSidesCycleMaterialsAndEndsRotateEveryAxis(GameTestHelper helper) {
      var registry = helper.getLevel().registryAccess().registryOrThrow(TinkerWoodFamily.REGISTRY);
      helper.assertTrue(registry.size() == 10, "built-in wood families were not loaded");
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (var family : registry) {
         for (Direction.Axis axis : Direction.Axis.values()) {
            var original = family.log().defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
            var side = axis == Direction.Axis.X ? Direction.NORTH : Direction.EAST;
            var current = original;
            for (var next : List.of(family.stripped(), family.bark(), family.log())) {
               helper.getLevel().setBlock(pos, current, FLAGS);
               current = BlockTinker.resolve(helper.getLevel(), hitAt(pos, side, 0.5, 0.5, 0.5)).get(pos);
               helper.assertTrue(current != null && current.is(next), "wood side cycle lost its material family");
               helper.assertTrue(current.getValue(BlockStateProperties.AXIS) == axis, "wood cycle changed axis");
            }
            for (Block block : List.of(family.log(), family.stripped())) {
               var state = block.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
               helper.getLevel().setBlock(pos, state, FLAGS);
               var rotated = BlockTinker.resolve(helper.getLevel(), hitAt(pos,
                  Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE), 0.5, 0.5, 0.5)).get(pos);
               helper.assertTrue(rotated.is(block) && rotated.getValue(BlockStateProperties.AXIS) != axis, "end-grain click changed material instead of axis");
            }
            helper.getLevel().setBlock(pos, family.bark().defaultBlockState().setValue(BlockStateProperties.AXIS, axis), FLAGS);
            var barkEnd = BlockTinker.resolve(helper.getLevel(), hitAt(pos,
               Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE), 0.5, 0.5, 0.5)).get(pos);
            helper.assertTrue(barkEnd.is(family.log()), "all-bark block invented an end-grain face");
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void groundAndGrassCyclesOnlyWriteTheClickedCell(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      level.setBlock(pos.above(), Blocks.GLASS.defaultBlockState(), FLAGS);
      level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), FLAGS);
      var lower = Blocks.TALL_GRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
         net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
      var upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
         net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
      for (var cycle : List.of(
         List.of(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState(),
            Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7), Blocks.FARMLAND.defaultBlockState()),
         List.of(Blocks.SHORT_GRASS.defaultBlockState(), lower, upper))) {
         for (int index = 0; index < cycle.size(); index++) {
            level.setBlock(pos, cycle.get(index), FLAGS);
            var changes = BlockTinker.resolve(level, hitAt(pos, Direction.UP, 0.5, 1, 0.5));
            helper.assertTrue(changes.size() == 1 && changes.get(pos).equals(cycle.get((index + 1) % cycle.size())), "single-cell cycle changed the wrong state");
            level.setBlock(pos, changes.get(pos), FLAGS);
            helper.assertTrue(level.getBlockState(pos.above()).is(Blocks.GLASS)
               && level.getBlockState(pos.below()).is(Blocks.STONE), "grass cycle changed another cell");
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void groundCyclesRespectClickedFaceAndMoisture(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var surfaces = List.of(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.PODZOL.defaultBlockState(),
         Blocks.MYCELIUM.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true));
      var dirt = List.of(Blocks.DIRT.defaultBlockState(), Blocks.COARSE_DIRT.defaultBlockState(), Blocks.ROOTED_DIRT.defaultBlockState());
      for (Direction face : Direction.values()) {
         var target = hitAt(pos, face, 0.5, 0.5, 0.5);
         for (int index = 0; index < surfaces.size(); index++) {
            level.setBlock(pos, surfaces.get(index), FLAGS);
            var expected = face == Direction.UP ? Blocks.DIRT_PATH.defaultBlockState() : surfaces.get((index + 1) % surfaces.size());
            helper.assertTrue(expected.equals(BlockTinker.resolve(level, target).get(pos)), "surface cycle ignored the clicked face");
         }
         for (int index = 0; index < dirt.size(); index++) {
            level.setBlock(pos, dirt.get(index), FLAGS);
            helper.assertTrue(dirt.get((index + 1) % dirt.size()).equals(BlockTinker.resolve(level, target).get(pos)), "dirt cycle failed on " + face);
         }
         level.setBlock(pos, Blocks.DIRT_PATH.defaultBlockState(), FLAGS);
         if (face != Direction.UP) helper.assertTrue(BlockTinker.resolve(level, target).isEmpty(), "path changed from a side or bottom click");
         for (int moisture = 0; moisture <= 7; moisture++) {
            level.setBlock(pos, Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, moisture), FLAGS);
            var changes = BlockTinker.resolve(level, target);
            if (face != Direction.UP) {
               helper.assertTrue(changes.isEmpty(), "farmland changed from a side or bottom click");
            } else {
               var expected = moisture == 0 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.FARMLAND.defaultBlockState();
               helper.assertTrue(expected.equals(changes.get(pos)), "farmland cycle ignored moisture " + moisture);
            }
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void torchCyclesKeepMountingAndFacing(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (Direction mounting : Direction.values()) {
         if (mounting == Direction.DOWN) continue;
         boolean wall = mounting.getAxis().isHorizontal();
         var normal = wall ? Blocks.WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, mounting)
            : Blocks.TORCH.defaultBlockState();
         var redstone = wall ? Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, mounting)
            : Blocks.REDSTONE_TORCH.defaultBlockState();
         var cycle = List.of(normal, redstone.setValue(BlockStateProperties.LIT, true));
         for (Direction face : Direction.values()) {
            for (int index = 0; index < cycle.size(); index++) {
               level.setBlock(pos, cycle.get(index), FLAGS);
               var changes = BlockTinker.resolve(level, hitAt(pos, face, 0.5, 0.5, 0.5));
               helper.assertTrue(changes.size() == 1 && cycle.get((index + 1) % cycle.size()).equals(changes.get(pos)),
                  "torch cycle lost mounting, facing or light state");
            }
            level.setBlock(pos, redstone.setValue(BlockStateProperties.LIT, false), FLAGS);
            helper.assertTrue(BlockTinker.resolve(level, hitAt(pos, face, 0.5, 0.5, 0.5)).isEmpty(),
               "existing unlit torch was changed by the wrench");
         }
      }
      for (Block block : List.of(Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH)) {
         level.setBlock(pos, block.defaultBlockState(), FLAGS);
         helper.assertTrue(BlockTinker.resolve(level, hitAt(pos, Direction.UP, 0.5, 0.5, 0.5)).isEmpty(), "soul torch entered the normal torch cycle");
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void genericRulesSkipIncompleteShapesButKeepFullBlocks(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      for (Block block : List.of(Blocks.LADDER, Blocks.END_ROD, Blocks.CHAIN, Blocks.LANTERN, Blocks.AMETHYST_CLUSTER)) {
         helper.getLevel().setBlock(pos, block.defaultBlockState(), FLAGS);
         for (Direction face : Direction.values()) {
            helper.assertTrue(BlockTinker.resolve(helper.getLevel(), hitAt(pos, face, 0.5, 0.5, 0.5)).isEmpty(), "generic rule changed incomplete block " + block);
         }
      }
      var dispenser = Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.NORTH);
      helper.getLevel().setBlock(pos, dispenser, FLAGS);
      helper.assertTrue(BlockTinker.resolve(helper.getLevel(), hitAt(pos, Direction.EAST, 1, 0.5, 0.5)).get(pos)
         .getValue(BlockStateProperties.FACING) == Direction.EAST, "full block lost generic facing");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void gateEdgesUseLocalAxesAndCenterUsesVanilla(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var player = helper.makeMockServerPlayerInLevel();
      for (Direction facing : Direction.Plane.HORIZONTAL) {
         for (boolean low : new boolean[] {false, true}) {
            var state = Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
               .setValue(BlockStateProperties.IN_WALL, low);
            level.setBlock(pos, state, FLAGS);
            double top = state.getShape(level, pos).max(Direction.Axis.Y);
            for (Direction vertical : List.of(Direction.UP, Direction.DOWN)) {
               var changed = BlockTinker.resolve(level, hitAt(pos, vertical, 0.5, 0.5, 0.5)).get(pos);
               helper.assertTrue(changed.getValue(BlockStateProperties.IN_WALL) != low, "gate top or bottom did not toggle in-wall");
            }
            for (Direction front : List.of(facing, facing.getOpposite())) {
               for (double y : new double[] {0.125, top - 0.125}) {
                  var changed = BlockTinker.resolve(level, hitAt(pos, front, 0.5, y, 0.5)).get(pos);
                  helper.assertTrue(changed.getValue(BlockStateProperties.IN_WALL) != low, "gate vertical edge ignored its actual height");
               }
               for (double across : new double[] {0.125, 0.875}) {
                  double x = facing.getAxis() == Direction.Axis.Z ? across : 0.5;
                  double z = facing.getAxis() == Direction.Axis.X ? across : 0.5;
                  var changed = BlockTinker.resolve(level, hitAt(pos, front, x, top / 2, z)).get(pos);
                  Direction expected = facing.getAxis() == Direction.Axis.Z
                     ? (across < 0.5 ? Direction.WEST : Direction.EAST) : (across < 0.5 ? Direction.NORTH : Direction.SOUTH);
                  helper.assertTrue(changed.getValue(BlockStateProperties.HORIZONTAL_FACING) == expected, "gate horizontal edge rotated the wrong way");
               }
               helper.assertTrue(BlockTinker.resolve(level, hitAt(pos, front, 0.5, top / 2, 0.5)).isEmpty(), "gate center blocked vanilla");
            }
            var side = facing.getClockWise();
            helper.assertTrue(BlockTinker.resolve(level, hitAt(pos, side, 0.5, top / 2, 0.5)).get(pos)
               .getValue(BlockStateProperties.HORIZONTAL_FACING) == side, "gate side did not change facing");
            player.setYRot(facing.getOpposite().toYRot());
            var center = hitAt(pos, facing, 0.5, top / 2, 0.5);
            state.useWithoutItem(level, player, center);
            var opened = level.getBlockState(pos);
            helper.assertTrue(opened.getValue(BlockStateProperties.OPEN)
               && opened.getValue(BlockStateProperties.HORIZONTAL_FACING) == player.getDirection(), "gate did not open in the vanilla direction");
            opened.useWithoutItem(level, player, center);
            helper.assertTrue(!level.getBlockState(pos).getValue(BlockStateProperties.OPEN), "gate did not close");
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void wallPostHasPriorityOnlyWhereItExists(GameTestHelper helper) {
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var wall = Blocks.COBBLESTONE_WALL.defaultBlockState().setValue(BlockStateProperties.UP, true)
         .setValue(BlockStateProperties.WEST_WALL, net.minecraft.world.level.block.state.properties.WallSide.LOW);
      level.setBlock(pos, wall, FLAGS);
      var post = BlockTinker.resolve(level, hitAt(pos, Direction.WEST, 0.25, 0.5, 0.5)).get(pos);
      helper.assertTrue(!post.getValue(BlockStateProperties.UP) && post.getValue(BlockStateProperties.WEST_WALL)
         == net.minecraft.world.level.block.state.properties.WallSide.LOW, "post click edited its connection");
      level.setBlock(pos, post, FLAGS);
      var arm = BlockTinker.resolve(level, hitAt(pos, Direction.WEST, 0.25, 0.5, 0.5)).get(pos);
      helper.assertTrue(!arm.getValue(BlockStateProperties.UP) && arm.getValue(BlockStateProperties.WEST_WALL)
         == net.minecraft.world.level.block.state.properties.WallSide.TALL, "absent post blocked connection edit");
      level.setBlock(pos, wall, FLAGS);
      var outside = BlockTinker.resolve(level, hitAt(pos, Direction.UP, 0.1, 0.875, 0.5)).get(pos);
      helper.assertTrue(outside.getValue(BlockStateProperties.UP) && outside.getValue(BlockStateProperties.WEST_WALL)
         == net.minecraft.world.level.block.state.properties.WallSide.TALL, "connection click changed the central post");
      helper.succeed();
   }

   private static BlockHitResult hitAt(BlockPos pos, Direction face, double x, double y, double z) {
      return new BlockHitResult(Vec3.atLowerCornerOf(pos).add(x, y, z), face, pos, false);
   }

   @GameTest(template = "fastformergametests.empty", batch = "tinker")
   public static void stairCornersCoverEveryFacingHalfAndShape(GameTestHelper helper) {
      var family = TinkerShapeFamilies.find(helper.getLevel().registryAccess(), Blocks.OAK_PLANKS).orElseThrow();
      for (Half half : Half.values()) {
         int shift = half == Half.BOTTOM ? 4 : 0;
         int base = half == Half.BOTTOM ? 15 : 240;
         Direction face = half == Half.BOTTOM ? Direction.UP : Direction.DOWN;
         for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (var shape : net.minecraft.world.level.block.state.properties.StairsShape.values()) {
               var source = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, half)
                  .setValue(BlockStateProperties.HORIZONTAL_FACING, facing).setValue(BlockStateProperties.STAIRS_SHAPE, shape)
                  .setValue(BlockStateProperties.WATERLOGGED, true);
               int original = TinkerShapeTransform.occupancy(source);
               for (int corner = 0; corner < 4; corner++) {
                  boolean raised = (original & (1 << (corner + shift))) != 0;
                  double y = raised ? (half == Half.BOTTOM ? 1 : 0) : 0.5;
                  var target = hitAt(BlockPos.ZERO, face, (corner & 1) == 0 ? 0.25 : 0.75, y, (corner & 2) == 0 ? 0.25 : 0.75);
                  int desired = ((original >> shift) & 15) ^ (1 << corner);
                  boolean representable = desired != 6 && desired != 9;
                  var result = TinkerShapeTransform.apply(family, source, target);
                  int expected = representable ? base | (desired << shift) : original;
                  helper.assertTrue(TinkerShapeTransform.occupancy(result) == expected, "corner edit changed another corner or created an invalid shape");
                  if (result.hasProperty(BlockStateProperties.WATERLOGGED)) {
                     helper.assertTrue(result.getValue(BlockStateProperties.WATERLOGGED), "corner edit lost water");
                  }
                  var isolated = TinkerShapeTransform.stairsOnly(source, target);
                  int isolatedExpected = representable && desired != 0 && desired != 15 ? expected : original;
                  helper.assertTrue(TinkerShapeTransform.occupancy(isolated) == isolatedExpected, "unmapped stairs lost a valid shape edit");
                  helper.assertTrue(result.equals(TinkerShapeTransform.apply(family, source, target)), "corner edit depends on iteration history");
               }
            }
         }
      }
      helper.succeed();
   }

}
