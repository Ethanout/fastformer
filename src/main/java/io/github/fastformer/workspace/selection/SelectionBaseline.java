package io.github.fastformer.workspace.selection;

import io.github.fastformer.common.world.BlockSnapshot;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.preview.Composition;
import io.github.fastformer.workspace.preview.CompositionBudget;
import io.github.fastformer.workspace.preview.WorkspacePreviewComposer;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.geometry.BlockPositionMaps;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Snapshot retained only while a transformed part is locked. */
public record SelectionBaseline(OperationSelectionVolume selection, WorkspaceTransform transform, Map<BlockPos, BlockSnapshot> sourceSnapshot) {
   public SelectionBaseline {
      sourceSnapshot = BlockPositionMaps.copyOf(sourceSnapshot);
   }

   public boolean matches(OperationSelectionVolume currentSelection, WorkspaceTransform currentTransform, Map<BlockPos, BlockSnapshot> currentSource) {
      return java.util.Objects.equals(this.selection, currentSelection) && this.transform.equals(currentTransform) && this.sourceSnapshot.equals(currentSource);
   }

   /** Compare full voxel contents, not transform parameters or visible bounds. */
   public boolean matchesBlocks(OperationSelectionVolume currentSelection,
      Map<BlockPos, BlockSnapshot> blocks, WorkspaceTransform candidate) {
      if (!sameBounds(this.selection, currentSelection)) return false;
      if (this.transform.equals(candidate) && this.sourceSnapshot.equals(blocks)) return true;
      if (this.sourceSnapshot.isEmpty() || blocks.isEmpty()) return false;
      Composition<BlockSnapshot> original = WorkspacePreviewComposer.composeSnapshots(
         this.sourceSnapshot, this.transform, CompositionBudget.INTERACTION);
      Composition<BlockSnapshot> current = WorkspacePreviewComposer.composeSnapshots(
         blocks, candidate, CompositionBudget.INTERACTION);
      if (!(original instanceof Composition.Composed<BlockSnapshot> before)
         || !(current instanceof Composition.Composed<BlockSnapshot> after)) return false;
      Map<BlockPos, BlockSnapshot> expected = before.values();
      Map<BlockPos, BlockSnapshot> actual = after.values();
      return expected.size() == actual.size()
         && expected.hashCode() == actual.hashCode()
         && expected.equals(actual);
   }

   private static boolean sameBounds(OperationSelectionVolume left, OperationSelectionVolume right) {
      return left != null && right != null && java.util.Objects.equals(left.bounds(), right.bounds());
   }
}
