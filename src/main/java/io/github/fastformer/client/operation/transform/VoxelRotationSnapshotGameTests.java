package io.github.fastformer.client.operation.transform;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Real furnace states after a typed rotation. These cases need the bootstrapped block
 * registry, so they run as GameTests rather than ordinary JUnit.
 */
@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VoxelRotationSnapshotGameTests {
   private static final double QUARTER = Math.PI / 2.0;

   private VoxelRotationSnapshotGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void positiveQuarterTurnTurnsTheStateWithItsCell(GameTestHelper helper) {
      VoxelRotation.RotationResult<ClientBlockSnapshot> rotated = VoxelRotation.rotateStageSnapshots(
         northSouthLine(), new Vec3(0.0, QUARTER, 0.0)
      );

      helper.assertTrue(
         Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)).equals(rotated.values().keySet()),
         "expected cells (0,0,0) and (1,0,0) but was " + rotated.values().keySet()
      );
      helper.assertTrue(
         facing(rotated.values().get(new BlockPos(1, 0, 0))) == Direction.WEST,
         "a positive Y quarter turn must face the furnace west"
      );
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void negativeQuarterTurnIsTheOppositeOfThePositiveOne(GameTestHelper helper) {
      VoxelRotation.RotationResult<ClientBlockSnapshot> rotated = VoxelRotation.rotateStageSnapshots(
         northSouthLine(), new Vec3(0.0, -QUARTER, 0.0)
      );

      helper.assertTrue(
         Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)).equals(rotated.values().keySet()),
         "expected cells (0,0,0) and (1,0,0) but was " + rotated.values().keySet()
      );
      helper.assertTrue(
         facing(rotated.values().get(BlockPos.ZERO)) == Direction.EAST,
         "a negative Y quarter turn must face the furnace east"
      );
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void halfTurnTurnsTheStateWithItsCell(GameTestHelper helper) {
      VoxelRotation.RotationResult<ClientBlockSnapshot> rotated = VoxelRotation.rotateStageSnapshots(
         northSouthLine(), new Vec3(0.0, Math.PI, 0.0)
      );

      helper.assertTrue(
         Set.of(BlockPos.ZERO, new BlockPos(0, 0, -1)).equals(rotated.values().keySet()),
         "expected cells (0,0,0) and (0,0,-1) but was " + rotated.values().keySet()
      );
      helper.assertTrue(
         facing(rotated.values().get(new BlockPos(0, 0, -1))) == Direction.SOUTH,
         "a Y half turn must face the furnace south"
      );
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void aNonRightAngleMovesTheCellAndKeepsTheState(GameTestHelper helper) {
      Map<BlockPos, ClientBlockSnapshot> source = northSouthLine();
      BlockState before = source.get(BlockPos.ZERO).state();

      VoxelRotation.RotationResult<ClientBlockSnapshot> rotated = VoxelRotation.rotateStageSnapshots(
         source, new Vec3(0.0, Math.toRadians(45.0), 0.0)
      );

      helper.assertTrue(
         before.equals(rotated.values().get(BlockPos.ZERO).state()),
         "a non-right angle must not invent a block orientation"
      );
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void anAxisWithoutABlockStateRotationKeepsTheState(GameTestHelper helper) {
      Map<BlockPos, ClientBlockSnapshot> source = new LinkedHashMap<>();
      source.put(BlockPos.ZERO, snapshot(furnaceFacingNorth()));
      source.put(new BlockPos(0, 1, 0), snapshot(Blocks.STONE.defaultBlockState()));

      VoxelRotation.RotationResult<ClientBlockSnapshot> rotated = VoxelRotation.rotateStageSnapshots(
         source, new Vec3(QUARTER, 0.0, 0.0)
      );

      helper.assertTrue(
         Set.of(new BlockPos(0, 1, 0), new BlockPos(0, 1, 1)).equals(rotated.values().keySet()),
         "expected cells (0,1,0) and (0,1,1) but was " + rotated.values().keySet()
      );
      helper.assertTrue(
         rotated.values().get(new BlockPos(0, 1, 0)).state().is(Blocks.FURNACE),
         "the furnace must land at (0,1,0)"
      );
      helper.assertTrue(
         facing(rotated.values().get(new BlockPos(0, 1, 0))) == Direction.NORTH,
         "an X turn moves the cell and must leave the facing alone"
      );
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void theComposerTurnsStatesForSnapshots(GameTestHelper helper) {
      WorkspaceTransform transform = new WorkspaceTransform(
         Vec3.ZERO, new Vec3(0.0, QUARTER, 0.0), OperationStackRegion.origin()
      );

      Map<BlockPos, ClientBlockSnapshot> typed = WorkspacePreviewComposer.resolveSnapshots(
         northSouthLine(), transform
      );
      helper.assertTrue(
         Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)).equals(typed.keySet()),
         "expected cells (0,0,0) and (1,0,0) but was " + typed.keySet()
      );
      helper.assertTrue(
         facing(typed.get(new BlockPos(1, 0, 0))) == Direction.WEST,
         "the composer must turn the furnace west with its cell"
      );
      helper.succeed();
   }

   /** A north facing furnace at the origin, with its neighbour to the north at -Z. */
   private static Map<BlockPos, ClientBlockSnapshot> northSouthLine() {
      Map<BlockPos, ClientBlockSnapshot> source = new LinkedHashMap<>();
      source.put(BlockPos.ZERO, snapshot(furnaceFacingNorth()));
      source.put(new BlockPos(0, 0, -1), snapshot(Blocks.STONE.defaultBlockState()));
      return source;
   }

   private static BlockState furnaceFacingNorth() {
      return Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
   }

   private static ClientBlockSnapshot snapshot(BlockState state) {
      return new ClientBlockSnapshot(state, null);
   }

   private static Direction facing(ClientBlockSnapshot snapshot) {
      return snapshot.state().getValue(HorizontalDirectionalBlock.FACING);
   }
}
