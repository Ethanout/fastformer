package io.github.fastformer.workspace.transform;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.preview.WorkspacePreviewComposer;
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
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
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
   public static void anUnrepresentableHorizontalFacingKeepsTheState(GameTestHelper helper) {
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
         "a furnace cannot face up after this X turn, so it must keep its facing"
      );
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void sixDirectionStatesTurnOnEveryAxis(GameTestHelper helper) {
      for (var block : new net.minecraft.world.level.block.Block[]{
         Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.PISTON_HEAD, Blocks.OBSERVER,
         Blocks.DISPENSER, Blocks.END_ROD, Blocks.AMETHYST_CLUSTER
      }) {
         for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
            for (Direction facing : Direction.values()) {
               BlockState source = block.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
               for (int turns = -4; turns <= 4; turns++) {
                  BlockState rotated = turnSnapshot(source, axis, turns).state();
                  helper.assertTrue(rotated.getValue(BlockStateProperties.FACING) == expectedFacing(facing, axis, turns),
                     "wrong facing for " + block + ", " + axis + ", " + facing + ", " + turns);
                  helper.assertTrue(turnSnapshot(rotated, axis, -turns).state().equals(source),
                     "inverse rotation must restore " + source);
               }
            }
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void pillarAxesAndSnapshotDataSurviveRotation(GameTestHelper helper) {
      for (var block : new net.minecraft.world.level.block.Block[]{Blocks.OAK_LOG, Blocks.CHAIN, Blocks.BASALT}) {
         for (Direction.Axis directionAxis : Direction.Axis.values()) {
            for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
               BlockState source = block.defaultBlockState().setValue(BlockStateProperties.AXIS, directionAxis);
               BlockState rotated = turnSnapshot(source, axis, 1).state();
               Direction vector = Direction.fromAxisAndDirection(directionAxis, Direction.AxisDirection.POSITIVE);
               helper.assertTrue(rotated.getValue(BlockStateProperties.AXIS) == expectedFacing(vector, axis, 1).getAxis(),
                  "wrong pillar axis for " + source + " around " + axis);
            }
         }
      }
      CompoundTag data = new CompoundTag();
      data.putString("marker", "preserve");
      var source = new ClientBlockSnapshot(Blocks.PISTON_HEAD.defaultBlockState(), data);
      var rotated = VoxelRotation.snapshotValues().forAxis(AxisGizmo.Axis.X, QUARTER).apply(source);
      helper.assertTrue(data.equals(rotated.blockEntity()), "rotation must preserve snapshot data");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void stairsMatchTheirRotatedShapeAndPreserveWater(GameTestHelper helper) {
      int matchedQuarterTurns = 0;
      int unmatchedQuarterTurns = 0;
      for (Direction facing : StairBlock.FACING.getPossibleValues()) {
         for (Half half : Half.values()) {
            for (StairsShape shape : StairsShape.values()) {
               BlockState source = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing)
                  .setValue(StairBlock.HALF, half).setValue(StairBlock.SHAPE, shape)
                  .setValue(StairBlock.WATERLOGGED, true);
               for (AxisGizmo.Axis axis : new AxisGizmo.Axis[]{AxisGizmo.Axis.X, AxisGizmo.Axis.Z}) {
                  for (int turns : new int[]{-1, 1, 2}) {
                     VoxelShape expected = rotatedShape(source, axis, turns);
                     BlockState rotated = turnSnapshot(source, axis, turns).state();
                     boolean representable = hasStairShape(expected);
                     if (representable) {
                        helper.assertTrue(sameShape(expected, blockShape(rotated)),
                           "stairs must match rotated geometry: " + source + ", " + axis + ", " + turns);
                        helper.assertTrue(sameShape(blockShape(source), blockShape(turnSnapshot(rotated, axis, -turns).state())),
                           "inverse stair rotation must restore the shape");
                        if (Math.abs(turns) == 1) matchedQuarterTurns++;
                     } else {
                        helper.assertTrue(source.equals(rotated), "an unrepresentable stair shape must stay unchanged");
                        if (Math.abs(turns) == 1) unmatchedQuarterTurns++;
                     }
                     helper.assertTrue(rotated.getValue(StairBlock.WATERLOGGED), "rotation must preserve water");
                  }
               }
            }
         }
      }
      helper.assertTrue(matchedQuarterTurns > 0 && unmatchedQuarterTurns > 0,
         "tests must cover representable and unrepresentable stair quarter turns");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 40)
   public static void theComposerTurnsPistonHeadsOnXAndZ(GameTestHelper helper) {
      for (AxisGizmo.Axis axis : new AxisGizmo.Axis[]{AxisGizmo.Axis.X, AxisGizmo.Axis.Z}) {
         Vec3 rotation = axis == AxisGizmo.Axis.X ? new Vec3(QUARTER, 0, 0) : new Vec3(0, 0, QUARTER);
         BlockState source = Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
         var transform = new WorkspaceTransform(Vec3.ZERO, rotation, OperationStackRegion.origin());
         var composed = WorkspacePreviewComposer.resolveSnapshots(Map.of(BlockPos.ZERO, snapshot(source)), transform);
         helper.assertTrue(composed.get(BlockPos.ZERO).state().getValue(BlockStateProperties.FACING)
            == expectedFacing(Direction.UP, axis, 1), "composer must rotate piston heads on " + axis);
      }
      helper.succeed();
   }

   private static ClientBlockSnapshot turnSnapshot(BlockState state, AxisGizmo.Axis axis, int turns) {
      return VoxelRotation.snapshotValues().forAxis(axis, QUARTER * turns).apply(snapshot(state));
   }

   private static Direction expectedFacing(Direction direction, AxisGizmo.Axis axis, int turns) {
      Direction[] cycle = switch (axis) {
         case X -> new Direction[]{Direction.UP, Direction.SOUTH, Direction.DOWN, Direction.NORTH};
         case Y -> new Direction[]{Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST};
         case Z -> new Direction[]{Direction.UP, Direction.WEST, Direction.DOWN, Direction.EAST};
      };
      for (int index = 0; index < cycle.length; index++) {
         if (cycle[index] == direction) return cycle[Math.floorMod(index + turns, 4)];
      }
      return direction;
   }

   private static VoxelShape blockShape(BlockState state) {
      return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
   }

   private static VoxelShape rotatedShape(BlockState state, AxisGizmo.Axis axis, int turns) {
      VoxelShape result = Shapes.empty();
      Vec3 center = new Vec3(0.5, 0.5, 0.5);
      for (var box : blockShape(state).toAabbs()) {
         Vec3 first = VoxelRotation.rotatePoint(new Vec3(box.minX, box.minY, box.minZ), center, axis, turns * QUARTER);
         Vec3 second = VoxelRotation.rotatePoint(new Vec3(box.maxX, box.maxY, box.maxZ), center, axis, turns * QUARTER);
         result = Shapes.or(result, Shapes.box(Math.min(first.x, second.x), Math.min(first.y, second.y), Math.min(first.z, second.z),
            Math.max(first.x, second.x), Math.max(first.y, second.y), Math.max(first.z, second.z)));
      }
      return result;
   }

   private static boolean hasStairShape(VoxelShape expected) {
      for (Direction facing : StairBlock.FACING.getPossibleValues()) {
         for (Half half : Half.values()) {
            for (StairsShape shape : StairsShape.values()) {
               BlockState candidate = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing)
                  .setValue(StairBlock.HALF, half).setValue(StairBlock.SHAPE, shape);
               if (sameShape(expected, blockShape(candidate))) return true;
            }
         }
      }
      return false;
   }

   private static boolean sameShape(VoxelShape first, VoxelShape second) {
      return !Shapes.joinIsNotEmpty(first, second, BooleanOp.NOT_SAME);
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
