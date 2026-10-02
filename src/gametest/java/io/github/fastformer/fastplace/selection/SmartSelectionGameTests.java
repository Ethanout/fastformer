package io.github.fastformer.fastplace.selection;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.selection.SmartSelectionEdits;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.session.ClientOperationDraft;
import io.github.fastformer.client.session.ClientOperationDraftCodec;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import java.util.Map;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SmartSelectionGameTests {
   private SmartSelectionGameTests() { }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 20)
   public static void adjacentAirCellsRemainEditableAndSurviveDraftHistory(GameTestHelper helper) throws Exception {
      var workspace = new ClientOperationWorkspace();
      var seed = helper.absolutePos(new BlockPos(1, 1, 1));
      var neighbor = seed.east();
      helper.getLevel().setBlock(neighbor, Blocks.AIR.defaultBlockState(), 2);
      SmartSelectionEdits.add(workspace, Map.of(seed, new ClientBlockSnapshot(Blocks.OAK_LOG.defaultBlockState(), null)));
      var air = io.github.fastformer.client.operation.selection.SmartSelectionCapture.captureCell(helper.getLevel(), neighbor);
      helper.assertTrue(air != null && air.state().isAir(), "Air was dropped during single-cell capture");
      helper.assertTrue(SmartSelectionEdits.addCell(workspace, neighbor, air), "Adjacent air could not be added");
      helper.assertFalse(SmartSelectionEdits.addCell(workspace, neighbor, air), "Repeated addition created an edit");
      var beforeRemoval = workspace.draftState();
      helper.assertTrue(SmartSelectionEdits.remove(workspace, neighbor, true), "Selected air could not be removed");
      helper.assertTrue(workspace.undo(), "Air removal had no undo");
      helper.assertTrue(workspace.draftState().equals(beforeRemoval), "Undo lost selected air");
      var part = workspace.parts().getFirst();
      var hit = SmartSelectionRaycast.pick(part.blocks(), null, CollisionContext.empty(),
         Vec3.atCenterOf(neighbor).add(0, 0, -3), new Vec3(0, 0, 1), 10);
      helper.assertTrue(hit != null && hit.position().equals(neighbor), "Selected air had no pick surface");
      var draft = new ClientOperationDraft(null, beforeRemoval,
         new ClientSelectionSession.DraftState(OperationSelectionMode.SMART, List.of(), 0, null, null));
      var decoded = ClientOperationDraftCodec.decode(ClientOperationDraftCodec.encode(draft),
         helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK));
      helper.assertTrue(decoded.workspace().equals(beforeRemoval), "Draft serialization lost air membership");
      SmartSelectionEdits.fix(workspace);
      helper.assertFalse(SmartSelectionEdits.addCell(workspace, neighbor.east(), air), "Fixed selection accepted air expansion");
      helper.assertTrue(helper.getLevel().getBlockState(neighbor).isAir(), "Selection editing wrote to the world");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 20)
   public static void similarityIgnoresStateAndUsesDedicatedFamilyTags(GameTestHelper helper) {
      var fence = Blocks.OAK_FENCE.defaultBlockState();
      var normal = SmartSelectionSimilarity.fromSeed(fence, false);
      helper.assertTrue(normal.test(fence.setValue(FenceBlock.WEST, true)), "Connection state changed the match");
      helper.assertFalse(normal.test(Blocks.OAK_PLANKS.defaultBlockState()), "Exact match accepted another block");
      var family = SmartSelectionSimilarity.fromSeed(fence, true);
      helper.assertTrue(family.test(Blocks.OAK_STAIRS.defaultBlockState()), "Oak family tag was not loaded");
      helper.assertFalse(family.test(Blocks.SPRUCE_STAIRS.defaultBlockState()), "Broad stair tag joined unrelated materials");
      var fallback = SmartSelectionSimilarity.fromSeed(Blocks.DIAMOND_BLOCK.defaultBlockState(), true);
      helper.assertTrue(fallback.test(Blocks.DIAMOND_BLOCK.defaultBlockState()), "Untagged seed cannot match itself");
      helper.assertFalse(fallback.test(Blocks.EMERALD_BLOCK.defaultBlockState()), "Untagged seed has an implicit family");
      helper.assertFalse(family.test(Blocks.AIR.defaultBlockState()), "Air entered a selection");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 20)
   public static void smartRayUsesOutlineShapesAndLeavesGapsOpen(GameTestHelper helper) {
      var slab = new ClientBlockSnapshot(Blocks.STONE_SLAB.defaultBlockState(), null);
      var blocks = Map.of(BlockPos.ZERO, slab, new BlockPos(8, 0, 0), slab);
      var direction = new Vec3(0, 0, 1);
      helper.assertTrue(SmartSelectionRaycast.hit(blocks, null, CollisionContext.empty(), new Vec3(0.5, 0.25, -3), direction, 10) != null,
         "Occupied slab was not hit");
      helper.assertTrue(SmartSelectionRaycast.hit(blocks, null, CollisionContext.empty(), new Vec3(0.5, 0.8, -3), direction, 10) == null,
         "Slab empty half became solid");
      helper.assertTrue(SmartSelectionRaycast.hit(blocks, null, CollisionContext.empty(), new Vec3(4.5, 0.25, -3), direction, 10) == null,
         "Gap between pieces became solid");
      var cell = SmartSelectionRaycast.pickCells(blocks.keySet(), new Vec3(0.5, 0.8, -3), direction, 10);
      helper.assertTrue(cell != null && cell.position().equals(BlockPos.ZERO), "Isolation did not target the full slab cell");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 20)
   public static void cellRemovalAndMixedDraftRoundTripKeepMembership(GameTestHelper helper) throws Exception {
      var fence = new ClientBlockSnapshot(Blocks.OAK_FENCE.defaultBlockState(), null);
      var connectedFence = new ClientBlockSnapshot(Blocks.OAK_FENCE.defaultBlockState().setValue(FenceBlock.WEST, true), null);
      var planks = new ClientBlockSnapshot(Blocks.OAK_PLANKS.defaultBlockState(), null);
      var workspace = new ClientOperationWorkspace();
      SmartSelectionEdits.add(workspace, Map.of(BlockPos.ZERO, fence, BlockPos.ZERO.east(), planks, BlockPos.ZERO.east(2), connectedFence));
      var before = workspace.parts();
      helper.assertTrue(SmartSelectionEdits.remove(workspace, BlockPos.ZERO, true), "Cell removal did not run");
      helper.assertTrue(workspace.parts().getFirst().blocks().keySet().equals(java.util.Set.of(BlockPos.ZERO.east(), BlockPos.ZERO.east(2))),
         "Cell removal changed another block of the same type");
      helper.assertTrue(workspace.undo(), "Cell removal had no undo");
      helper.assertTrue(workspace.parts().equals(before), "Undo lost source snapshots");
      helper.assertTrue(SmartSelectionEdits.remove(workspace, BlockPos.ZERO, false), "Component removal did not run");
      helper.assertTrue(workspace.isEmpty(), "Component removal stopped at a different block type");
      helper.assertTrue(workspace.undo(), "Component removal had no undo");
      SmartSelectionEdits.remove(workspace, BlockPos.ZERO.east(), true);
      workspace.addParts(List.of(new io.github.fastformer.workspace.model.ClientSelectionPart(0,
         io.github.fastformer.workspace.model.ClientSelectionPart.Source.WORLD,
         OperationSelectionVolume.cuboid(new BlockPos(20, 0, 0), new BlockPos(20, 0, 0), null, null),
         Map.of(new BlockPos(20, 0, 0), planks), io.github.fastformer.workspace.model.WorkspaceTransform.IDENTITY, false)));
      var draft = new ClientOperationDraft(null, workspace.draftState(),
         new ClientSelectionSession.DraftState(OperationSelectionMode.SMART, List.of(), 0, null, null));
      var decoded = ClientOperationDraftCodec.decode(ClientOperationDraftCodec.encode(draft),
         helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK));
      helper.assertTrue(decoded.workspace().equals(draft.workspace()), "Mixed selection draft changed during serialization");
      helper.assertTrue(decoded.selection().selectionMode() == OperationSelectionMode.SMART, "Tool mode changed during serialization");
      helper.assertTrue(SmartSelectionTopology.of(decoded.workspace().parts().getFirst().blocks().keySet()).disconnected(),
         "Disconnected membership was replaced by its envelope");
      helper.succeed();
   }
}
