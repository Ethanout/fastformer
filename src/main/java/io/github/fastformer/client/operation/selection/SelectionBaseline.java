package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.preview.Composition;
import io.github.fastformer.client.operation.preview.CompositionBudget;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.geometry.BlockPositionMaps;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Snapshot retained only while a transformed part is locked. */
public record SelectionBaseline(OperationSelectionVolume selection, WorkspaceTransform transform, Map<BlockPos, ClientBlockSnapshot> sourceSnapshot) {
   public SelectionBaseline {
      sourceSnapshot = BlockPositionMaps.copyOf(sourceSnapshot);
   }

   public boolean matches(OperationSelectionVolume currentSelection, WorkspaceTransform currentTransform, Map<BlockPos, ClientBlockSnapshot> currentSource) {
      return java.util.Objects.equals(this.selection, currentSelection) && this.transform.equals(currentTransform) && this.sourceSnapshot.equals(currentSource);
   }

   /** Compare full voxel contents, not transform parameters or visible bounds. */
   public boolean matchesBlocks(OperationSelectionVolume currentSelection,
      Map<BlockPos, ClientBlockSnapshot> blocks, WorkspaceTransform candidate) {
      if (!sameBounds(this.selection, currentSelection)) return false;
      if (this.transform.equals(candidate) && this.sourceSnapshot.equals(blocks)) return true;
      if (this.sourceSnapshot.isEmpty() || blocks.isEmpty()) return false;
      Composition<ClientBlockSnapshot> original = WorkspacePreviewComposer.composeSnapshots(
         this.sourceSnapshot, this.transform, CompositionBudget.INTERACTION);
      Composition<ClientBlockSnapshot> current = WorkspacePreviewComposer.composeSnapshots(
         blocks, candidate, CompositionBudget.INTERACTION);
      if (!(original instanceof Composition.Composed<ClientBlockSnapshot> before)
         || !(current instanceof Composition.Composed<ClientBlockSnapshot> after)) return false;
      Map<BlockPos, ClientBlockSnapshot> expected = before.values();
      Map<BlockPos, ClientBlockSnapshot> actual = after.values();
      return expected.size() == actual.size()
         && expected.hashCode() == actual.hashCode()
         && expected.equals(actual);
   }

   private static boolean sameBounds(OperationSelectionVolume left, OperationSelectionVolume right) {
      return left != null && right != null && java.util.Objects.equals(left.bounds(), right.bounds());
   }
}
