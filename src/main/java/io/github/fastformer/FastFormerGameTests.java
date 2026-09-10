package io.github.fastformer;

import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.geometry.generation.BlockPositionSource;
import io.github.fastformer.fastplace.geometry.generation.LineGenerator;
import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

/** Runtime checks kept separate from player workflow tests. */
@GameTestHolder(FastFormer.MOD_ID)
public final class FastFormerGameTests {
   @GameTest(template = "empty", timeoutTicks = 20)
   public static void statusMessageSurvivesSystemChatCodec(GameTestHelper helper) {
      var message = io.github.fastformer.fastplace.FastPlaceMessages.text(
         "fastformer.message.operation_failed_rollback", "写入",
         UUID.fromString("84143053-e7df-416c-a2d0-91de44255dbe"),
         "operation=84143053-e7df-416c-a2d0-91de44255dbe, phase=JOURNAL, targets=684, snapshots=684, writes=5, placed=4",
         "x".repeat(2000), Double.NaN,
         Float.POSITIVE_INFINITY, -30000000, 262144,
         io.github.fastformer.fastplace.FastPlaceMessages.text(
            "fastformer.test.nested", net.minecraft.network.chat.Component.literal("selection")
               .withStyle(net.minecraft.ChatFormatting.GOLD).append(" bounds")
         ),
         net.minecraft.network.chat.Component.literal("x".repeat(511) + "\uD83D\uDE00" + "y".repeat(600))
      );
      var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
         io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess()
      );
      try {
         boolean rejectedRawId = false;
         try {
            var unsafe = net.minecraft.network.chat.Component.translatable(
               "fastformer.message.operation_failed_rollback", "写入",
               UUID.fromString("84143053-e7df-416c-a2d0-91de44255dbe"), "phase=JOURNAL, targets=684"
            );
            net.minecraft.network.protocol.game.ClientboundSystemChatPacket.STREAM_CODEC.encode(
               buffer, new net.minecraft.network.protocol.game.ClientboundSystemChatPacket(unsafe, true)
            );
         } catch (io.netty.handler.codec.EncoderException | IllegalArgumentException expected) {
            rejectedRawId = true;
         }
         helper.assertTrue(rejectedRawId, "raw UUID no longer reproduces the reported encoding failure");
         buffer.clear();
         var packet = new net.minecraft.network.protocol.game.ClientboundSystemChatPacket(message, true);
         net.minecraft.network.protocol.game.ClientboundSystemChatPacket.STREAM_CODEC.encode(buffer, packet);
         var decoded = net.minecraft.network.protocol.game.ClientboundSystemChatPacket.STREAM_CODEC.decode(buffer);
         helper.assertTrue(message.getString().equals(decoded.content().getString()), "status text changed during encoding");
         helper.assertTrue(styledCharacters(message).equals(styledCharacters(decoded.content())),
            "status formatting changed during encoding");
         helper.assertTrue(decoded.overlay(), "status overlay flag changed");
         helper.assertTrue(buffer.readableBytes() == 0, "status packet left unread bytes");
         helper.succeed();
      } finally {
         buffer.release();
      }
   }

   private FastFormerGameTests() {
   }

   @GameTest(template = "empty", timeoutTicks = 20)
   public static void embeddedPlacementKeepsTheEntryPlane(GameTestHelper helper) {
      var level = helper.getLevel();
      var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
      var pos = helper.absolutePos(new BlockPos(1, 1, 1));
      level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
      var stack = new net.minecraft.world.item.ItemStack(Blocks.OAK_STAIRS);
      for (var face : net.minecraft.core.Direction.values()) {
         var location = net.minecraft.world.phys.Vec3.atCenterOf(pos)
            .add(net.minecraft.world.phys.Vec3.atLowerCornerOf(face.getNormal()).scale(0.5));
         var hit = new net.minecraft.world.phys.BlockHitResult(location, face, pos, false);
         var embedded = io.github.fastformer.fastplace.PlacementContextSnapshot.capture(level, player, stack, hit, true);
         var surface = io.github.fastformer.fastplace.PlacementContextSnapshot.capture(level, player, stack, hit, false);
         helper.assertTrue(embedded.clickedFace() == face, "embedded placement changed entry face");
         helper.assertTrue(embedded.hitLocation().equals(location), "embedded placement changed entry plane");
         helper.assertTrue(embedded.equals(surface), "placement mode changed orientation inputs");
      }
      helper.succeed();
   }

   @GameTest(template = "empty", timeoutTicks = 20)
   public static void woodFrameEffectAppliesLogStatesDuringLineAndFaceStages(GameTestHelper helper) {
      var modes = new io.github.fastformer.fastplace.FastPlaceGeometry.Modes(
         io.github.fastformer.fastplace.PointMode.RAYCAST,
         io.github.fastformer.fastplace.RaycastPlacement.EMBEDDED,
         io.github.fastformer.fastplace.LineMode.AXIS,
         io.github.fastformer.fastplace.FaceMode.COORDINATE_PLANE,
         io.github.fastformer.fastplace.VolumeMode.FREE,
         io.github.fastformer.fastplace.FillMode.OUTLINE, 0.0, false
      );
      var effect = new io.github.fastformer.fastplace.placement.effect.woodframe.WoodFramePlacementEffect();
      for (var points : List.of(
         List.of(BlockPos.ZERO, new BlockPos(6, 0, 0)),
         List.of(BlockPos.ZERO, new BlockPos(6, 0, 0), new BlockPos(0, 0, 4))
      )) {
         var context = new io.github.fastformer.fastplace.placement.effect.PlacementEffectContext(
            null, net.minecraft.world.item.ItemStack.EMPTY, Blocks.OAK_LOG.defaultBlockState(),
            net.minecraft.core.Direction.Axis.Y, points, modes, false,
            io.github.fastformer.fastplace.PolygonVolumeShape.EXTRUDE, null
         );
         helper.assertTrue(effect.matches(context), "wood effect rejected line or face stage");
         var targets = io.github.fastformer.fastplace.FastPlaceGeometry.blocks(
            points, modes, false, io.github.fastformer.fastplace.PolygonVolumeShape.EXTRUDE, 1000
         );
         var states = effect.resolve(context).stateOverrides().apply(targets);
         var axis = net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS;
         helper.assertTrue(states.get(new BlockPos(3, 0, 0)).getValue(axis) == net.minecraft.core.Direction.Axis.X,
            "line log axis is incorrect");
         if (points.size() == 3) {
            helper.assertTrue(states.get(new BlockPos(6, 0, 2)).getValue(axis) == net.minecraft.core.Direction.Axis.Z,
               "face log axis is incorrect");
         }
      }
      helper.succeed();
   }

   private static java.util.List<java.util.Map.Entry<net.minecraft.network.chat.Style, Integer>> styledCharacters(
      net.minecraft.network.chat.Component message
   ) {
      var characters = new java.util.ArrayList<java.util.Map.Entry<net.minecraft.network.chat.Style, Integer>>();
      message.visit((style, text) -> {
         text.codePoints().forEach(character -> characters.add(java.util.Map.entry(style, character)));
         return java.util.Optional.empty();
      }, net.minecraft.network.chat.Style.EMPTY);
      return characters;
   }

   @GameTest(template = "empty", timeoutTicks = 20)
   public static void placementRaySkipsReplaceableBlocksButKeepsTorch(GameTestHelper helper) {
      BlockPos support = helper.absolutePos(new BlockPos(1, 1, 1));
      BlockPos target = support.above();
      var level = helper.getLevel();
      var source = net.minecraft.world.entity.EntityType.ARMOR_STAND.create(level);
      level.setBlock(support, Blocks.STONE.defaultBlockState(), 2);
      var start = net.minecraft.world.phys.Vec3.atCenterOf(target).add(0, 3, 0);
      var down = new net.minecraft.world.phys.Vec3(0, -1, 0);
      for (var state : List.of(
         Blocks.SHORT_GRASS.defaultBlockState(),
         Blocks.SNOW.defaultBlockState(),
         Blocks.SNOW.defaultBlockState().setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS, 2)
      )) {
         level.setBlock(target, state, 2);
         var hit = io.github.fastformer.fastplace.LongRangeBlockRaycast.clipForPlacement(
            level, source, start, down
         ).hit();
         helper.assertTrue(hit.getBlockPos().equals(support), "replaceable block intercepted placement: " + state);
         helper.assertTrue(hit.getDirection() == net.minecraft.core.Direction.UP, "placement lost the entry face");
         helper.assertTrue(hit.getBlockPos().relative(hit.getDirection()).equals(target), "surface target changed");
      }
      level.setBlock(target, Blocks.TORCH.defaultBlockState(), 2);
      var hit = io.github.fastformer.fastplace.LongRangeBlockRaycast.clipForPlacement(level, source, start, down).hit();
      helper.assertTrue(hit.getBlockPos().equals(target), "nonreplaceable torch was skipped");
      helper.succeed();
   }

   public static void register(RegisterGameTestsEvent event) {
      event.register(FastFormerGameTests.class);
      event.register(io.github.fastformer.fastplace.world.JournalRecoveryGameTests.class);
   }

   @GameTest(template = "empty", timeoutTicks = 20)
   public static void modLoads(GameTestHelper helper) {
      helper.succeed();
   }

   @GameTest(template = "empty", timeoutTicks = 40)
   public static void lazyLineAndTransactionBoundariesWorkInRuntime(GameTestHelper helper) {
      var generated = LineGenerator.generate(BlockPos.ZERO, new BlockPos(100_000, 3, 2), 100_001);
      if (generated.size() != 100_001) {
         helper.fail("unexpected lazy line size: " + generated.size());
         return;
      }
      BlockGenerationResult result = BlockGenerationResult.fromLegacy(generated);
      BlockPositionSource source = result.positionSource();
      if (!source.supportsDraining() || !source.iterator().next().equals(BlockPos.ZERO)) {
         helper.fail("generated position source lost its ownership boundary");
         return;
      }

      WorldChangeTransaction transaction = new WorldChangeTransaction();
      BlockPos first = new BlockPos(4, 5, 6);
      BlockPos second = new BlockPos(-2, 3, 8);
      transaction.recordExpected(first, snapshot(first, "first"));
      transaction.recordExpected(second, snapshot(second, "second"));
      var positions = transaction.expectedPositions();
      if (!positions.hasNext() || !positions.next().equals(first)
         || !positions.hasNext() || !positions.next().equals(second) || positions.hasNext()) {
         helper.fail("transaction expected order changed");
         return;
      }
      helper.succeed();
   }

   @GameTest(template = "empty", timeoutTicks = 40)
   public static void worldRecoveryRestoresARealLevelBlock(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
      ReversibleBlockSnapshot before = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      level.setBlock(position, Blocks.STONE.defaultBlockState(), 3);
      ReversibleBlockSnapshot after = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      UUID owner = UUID.randomUUID();

      if (!WorldHistoryManager.startRollback(
         new WorldTaskContext(level.getServer(), owner),
         level.dimension(),
         new ArrayDeque<>(List.of(before)),
         Map.of(position, after),
         null
      )) {
         helper.fail("real-level recovery was not accepted");
         return;
      }

      helper.succeedWhen(() -> {
         if (level.getBlockState(position).equals(before.state()) && !WorldHistoryManager.busy(owner)) {
            helper.succeed();
         }
      });
   }

   @GameTest(template = "empty", timeoutTicks = 40)
   public static void worldRecoveryRestoresARealBlockEntity(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(2, 1, 1));
      level.setBlock(position, Blocks.CHEST.defaultBlockState(), 3);
      var before = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
      var after = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      UUID owner = UUID.randomUUID();

      if (!WorldHistoryManager.startRollback(
         new WorldTaskContext(level.getServer(), owner),
         level.dimension(),
         new ArrayDeque<>(List.of(before)),
         Map.of(position, after),
         null
      )) {
         helper.fail("block entity recovery was not accepted");
         return;
      }

      helper.succeedWhen(() -> {
         if (level.getBlockState(position).is(Blocks.CHEST)
            && level.getBlockEntity(position) != null
            && !WorldHistoryManager.busy(owner)) {
            helper.succeed();
         }
      });
   }

   @GameTest(template = "empty", timeoutTicks = 40)
   public static void worldRecoveryRestoresFluidState(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(3, 1, 1));
      level.setBlock(position, Blocks.WATER.defaultBlockState(), 3);
      var before = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
      var after = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      UUID owner = UUID.randomUUID();

      if (!WorldHistoryManager.startRollback(
         new WorldTaskContext(level.getServer(), owner),
         level.dimension(),
         new ArrayDeque<>(List.of(before)),
         Map.of(position, after),
         null
      )) {
         helper.fail("fluid recovery was not accepted");
         return;
      }

      helper.succeedWhen(() -> {
         if (level.getFluidState(position).is(Fluids.WATER)
            && !WorldHistoryManager.busy(owner)) {
            helper.succeed();
         }
      });
   }

   @GameTest(template = "empty", timeoutTicks = 40)
   public static void worldRecoveryPreservesExternalConflict(GameTestHelper helper) {
      var level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(4, 1, 1));
      level.setBlock(position, Blocks.STONE.defaultBlockState(), 3);
      ReversibleBlockSnapshot before = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      level.setBlock(position, Blocks.DIRT.defaultBlockState(), 3);
      ReversibleBlockSnapshot after = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      UUID owner = UUID.randomUUID();

      if (!WorldHistoryManager.startRollback(
         new WorldTaskContext(level.getServer(), owner),
         level.dimension(),
         new ArrayDeque<>(List.of(before)),
         Map.of(position, after),
         null
      )) {
         helper.fail("conflict recovery was not accepted");
         return;
      }

      // A later external edit must win. Recovery should report completion once
      // it has inspected the cell and must not overwrite the external state.
      level.setBlock(position, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
      helper.succeedWhen(() -> {
         if (level.getBlockState(position).is(Blocks.GOLD_BLOCK)
            && !WorldHistoryManager.busy(owner)) {
            helper.succeed();
         }
      });
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position, String marker) {
      var tag = new net.minecraft.nbt.CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(position, null, null, new BlockEntitySnapshot(tag));
   }
}
