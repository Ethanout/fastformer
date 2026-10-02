package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.interaction.SelectionGizmoInteraction;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.fastplace.selection.SmartSelectionTopology;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class SmartSelectionEditsTest {
   @Test void removingBridgeKeepsOneSelectionAndUndoRestoresSnapshot() throws Exception {
      var workspace = new ClientOperationWorkspace();
      var blocks = row(0, 1, 2);
      assertTrue(SmartSelectionEdits.add(workspace, blocks));
      int id = workspace.activeId();
      assertTrue(SmartSelectionEdits.remove(workspace, new BlockPos(1, 0, 0), true));
      assertEquals(1, workspace.size());
      assertEquals(id, workspace.activeId());
      var part = workspace.parts().getFirst();
      assertEquals(Set.of(BlockPos.ZERO, new BlockPos(2, 0, 0)), part.blocks().keySet());
      assertEquals(part.blocks(), part.sourceSnapshot());
      assertEquals(2, SmartSelectionTopology.of(part.blocks().keySet()).pieces().size());
      assertTrue(workspace.undo());
      assertEquals(blocks, workspace.parts().getFirst().blocks());
   }

   @Test void distantRegionsShareTheActiveGroupAndUndoRestoresMembership() throws Exception {
      var workspace = new ClientOperationWorkspace();
      SmartSelectionEdits.add(workspace, row(0));
      SmartSelectionEdits.add(workspace, row(200));
      assertEquals(1, workspace.size());
      var before = workspace.draftState();
      assertTrue(SmartSelectionEdits.add(workspace, row(1)));
      assertEquals(1, workspace.size());
      assertEquals(3, workspace.parts().getFirst().blocks().size());
      assertEquals(Set.of(workspace.parts().getFirst().id()), workspace.selectedIds());
      assertTrue(workspace.undo());
      assertEquals(before, workspace.draftState());
      assertTrue(SmartSelectionEdits.fix(workspace));
      SmartSelectionEdits.add(workspace, row(1));
      assertEquals(2, workspace.size());
      assertFalse(workspace.parts().getFirst().smartEditable());
   }

   @Test void smartEditHidesAllGizmosIncludingTheGroupUntilExplicitFix() throws Exception {
      var workspace = new ClientOperationWorkspace();
      SmartSelectionEdits.add(workspace, row(0));
      SmartSelectionEdits.fix(workspace);
      SmartSelectionEdits.add(workspace, row(10));
      workspace.selectAll();
      UUID owner = UUID.randomUUID();
      var scene = SelectionInteractionScene.capture(owner, workspace, null);
      assertNull(scene.groupGizmo());
      for (var part : scene.parts().values()) assertFalse(SelectionGizmoInteraction.partGizmoVisible(scene, part.gizmo(), true));
      SmartSelectionEdits.fix(workspace);
      scene = SelectionInteractionScene.capture(owner, workspace, scene);
      assertNotNull(scene.groupGizmo());
      assertTrue(workspace.parts().stream().noneMatch(ClientSelectionPart::smartEditable));
      assertTrue(workspace.undo());
      assertTrue(SmartSelectionEdits.editing(workspace));
      assertNull(SelectionInteractionScene.capture(owner, workspace, scene).groupGizmo());
   }

   @Test void decorativeEnvelopeNeverClaimsItsGapAsMembership() throws Exception {
      var volume = SmartSelectionEdits.volume(row(0, 100));
      var gap = new net.minecraft.world.phys.Vec3(50, 0.5, 0.5);
      assertFalse(volume.contains(gap));
      assertFalse(volume.intersects(new net.minecraft.world.phys.AABB(new BlockPos(50, 0, 0))));
      assertNull(volume.raycast(gap.add(0, 0, -10), new net.minecraft.world.phys.Vec3(0, 0, 1), 20));
   }

   @Test void ordinaryRemoveUsesCachedPieceAndPreservesDisconnectedMembers() throws Exception {
      var workspace = new ClientOperationWorkspace();
      SmartSelectionEdits.add(workspace, row(0, 1, 7, 8));
      var original = workspace.draftState();
      var part = workspace.parts().getFirst();
      var topology = workspace.smartTopology(part);
      assertSame(topology, workspace.smartTopology(part));
      assertEquals(Set.of(BlockPos.ZERO, BlockPos.ZERO.east()), topology.membersAt(BlockPos.ZERO));
      assertTrue(SmartSelectionEdits.remove(workspace, BlockPos.ZERO.east(), false));
      assertEquals(Set.of(BlockPos.ZERO.east(7), BlockPos.ZERO.east(8)), workspace.parts().getFirst().blocks().keySet());
      assertNotSame(topology, workspace.smartTopology(workspace.parts().getFirst()));
      var removed = workspace.draftState();
      assertTrue(workspace.undo());
      assertEquals(original, workspace.draftState());
      assertEquals(2, workspace.smartTopology(workspace.parts().getFirst()).pieces().size());
      workspace.restoreDraftState(removed);
      assertEquals(1, workspace.smartTopology(workspace.parts().getFirst()).pieces().size());
   }

   @Test void removingLastPieceIsUndoableAndFixedPartsRejectBothRemovalModes() throws Exception {
      var workspace = new ClientOperationWorkspace();
      SmartSelectionEdits.add(workspace, row(0, 1));
      assertTrue(SmartSelectionEdits.remove(workspace, BlockPos.ZERO, false));
      assertTrue(workspace.isEmpty());
      assertTrue(workspace.undo());
      SmartSelectionEdits.fix(workspace);
      var fixed = workspace.draftState();
      assertFalse(SmartSelectionEdits.remove(workspace, BlockPos.ZERO, false));
      assertFalse(SmartSelectionEdits.remove(workspace, BlockPos.ZERO, true));
      assertEquals(fixed, workspace.draftState());
   }

   @Test void membershipCacheSurvivesSelectionChangesAndInvalidatesAfterBridgeRemoval() throws Exception {
      var workspace = new ClientOperationWorkspace();
      SmartSelectionEdits.add(workspace, row(0, 1, 2));
      var part = workspace.parts().getFirst();
      var joined = workspace.smartTopology(part);
      workspace.selectAll();
      assertSame(joined, workspace.smartTopology(workspace.parts().getFirst()));
      SmartSelectionEdits.remove(workspace, BlockPos.ZERO.east(), true);
      var split = workspace.smartTopology(workspace.parts().getFirst());
      assertEquals(Set.of(BlockPos.ZERO), split.membersAt(BlockPos.ZERO));
      assertTrue(SmartSelectionEdits.remove(workspace, BlockPos.ZERO, false));
      assertEquals(Set.of(BlockPos.ZERO.east(2)), workspace.parts().getFirst().blocks().keySet());
   }

   static Map<BlockPos, ClientBlockSnapshot> row(int... positions) throws Exception {
      var field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      var snapshot = (ClientBlockSnapshot)((Unsafe)field.get(null)).allocateInstance(ClientBlockSnapshot.class);
      Map<BlockPos, ClientBlockSnapshot> blocks = new java.util.LinkedHashMap<>();
      for (int x : positions) blocks.put(new BlockPos(x, 0, 0), snapshot);
      return Map.copyOf(blocks);
   }

   @Test void readdingCellsKeepsTheirFirstSnapshot() throws Exception {
      var workspace = new ClientOperationWorkspace();
      var original = row(0);
      SmartSelectionEdits.add(workspace, original);
      SmartSelectionEdits.add(workspace, row(0, 400));
      assertSame(original.get(BlockPos.ZERO), workspace.parts().getFirst().blocks().get(BlockPos.ZERO));
      assertEquals(1, workspace.size());
   }

   @Test void addingAnotherPartFixesTheSmartGroupAndUndoRestoresItsEditability() throws Exception {
      var workspace = new ClientOperationWorkspace();
      SmartSelectionEdits.add(workspace, row(0));
      var before = workspace.draftState();
      var cuboid = io.github.fastformer.fastplace.selection.OperationSelectionVolume.create(
         io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID,
         List.of(new BlockPos(8, 0, 0)), 0, BlockPos.ZERO, BlockPos.ZERO, 0);
      workspace.addParts(List.of(new ClientSelectionPart(0, ClientSelectionPart.Source.WORLD, cuboid,
         row(8), io.github.fastformer.workspace.model.WorkspaceTransform.IDENTITY, false)));
      assertFalse(workspace.parts().getFirst().smartEditable());
      assertTrue(workspace.undo());
      assertEquals(before, workspace.draftState());
      assertTrue(SmartSelectionEdits.editing(workspace));
   }
}
