package io.github.fastformer.client.operation.workspace;

import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;

class SelectionDeletionPlanTest {
   @Test void overlappingDeletesAreMergedWithoutConsumingTheRetainedLayer() throws Exception {
      var snapshot = snapshot();
      var first = part(8, ClientSelectionPart.Source.WORLD,
         Map.of(BlockPos.ZERO, snapshot, BlockPos.ZERO.east(), snapshot), WorkspaceTransform.IDENTITY);
      var second = part(1, ClientSelectionPart.Source.WORLD, Map.of(BlockPos.ZERO, snapshot), WorkspaceTransform.IDENTITY);
      var retained = part(2, ClientSelectionPart.Source.WORLD, Map.of(BlockPos.ZERO.east(), snapshot), WorkspaceTransform.IDENTITY);
      var result = SelectionDeletionPlan.parts(List.of(first, second, retained), Set.of(8, 1));
      assertEquals(1, result.size());
      assertEquals(Set.of(BlockPos.ZERO), result.getFirst().blocks().keySet());
      assertEquals(8, result.getFirst().id());
   }
   @Test void deletionProtectsUnselectedSourcesAndUsesTheOriginalMovedSnapshot() throws Exception {
      var block = snapshot();
      var selected = part(1, ClientSelectionPart.Source.WORLD,
         Map.of(BlockPos.ZERO, block, BlockPos.ZERO.east(), block), WorkspaceTransform.IDENTITY.withTranslation(new Vec3(5, 0, 0)));
      var unselected = part(2, ClientSelectionPart.Source.WORLD,
         Map.of(BlockPos.ZERO.east(), block), WorkspaceTransform.IDENTITY);
      var plan = SelectionDeletionPlan.parts(List.of(selected, unselected), Set.of(1));
      assertEquals(1, plan.size());
      assertEquals(Set.of(BlockPos.ZERO), plan.getFirst().blocks().keySet());
      assertTrue(plan.getFirst().pendingDelete());
      assertFalse(selected.pendingDelete());
      var workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(selected, unselected));
      workspace.selectOnly(1);
      var remainder = SelectionDeletionPlan.remaining(workspace, Set.of(1));
      assertEquals(List.of(unselected), remainder.parts());
      assertTrue(remainder.selectedIds().isEmpty());
   }

   @Test void clipboardDeletionDoesNotDeleteBackgroundWorldBlocks() throws Exception {
      var pasted = part(1, ClientSelectionPart.Source.CLIPBOARD, Map.of(BlockPos.ZERO, snapshot()), WorkspaceTransform.IDENTITY);
      assertTrue(SelectionDeletionPlan.parts(List.of(pasted), Set.of(1)).isEmpty());
      assertTrue(SelectionDeletionPlan.parts(List.of(pasted), Set.of()).isEmpty());
   }

   private static ClientSelectionPart part(int id, ClientSelectionPart.Source source,
      Map<BlockPos, ClientBlockSnapshot> blocks, WorkspaceTransform transform) {
      return new ClientSelectionPart(id, source, null, blocks, transform, false, blocks, null, ClientSelectionPart.Editability.FREE);
   }

   private static ClientBlockSnapshot snapshot() throws Exception {
      var field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      return (ClientBlockSnapshot)((Unsafe)field.get(null)).allocateInstance(ClientBlockSnapshot.class);
   }
}
