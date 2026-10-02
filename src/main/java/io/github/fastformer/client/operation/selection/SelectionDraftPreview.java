package io.github.fastformer.client.operation.selection;

import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;

/** Confirmation and deletion use the same volume as the unfinished selection. */
public final class SelectionDraftPreview {
   private SelectionDraftPreview() { }
   public static OperationSelectionVolume volume(ClientSelectionSession.DraftState draft, BlockPos candidate) {
      if (draft.points().isEmpty()) return null;
      var points = new ArrayList<>(draft.points());
      if (candidate != null && !points.contains(candidate)) {
         if (draft.secondPointOnly()) points.addFirst(candidate);
         else points.add(candidate);
      }
      var volume = OperationSelectionVolume.create(draft.selectionMode(), points, draft.prismBaseCount(), BlockPos.ZERO, BlockPos.ZERO, 0);
      if (volume != null && draft.selectionMode() == io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID) {
         volume = volume.expandCuboidTo(draft.minPoint()).expandCuboidTo(draft.maxPoint());
      }
      return volume;
   }
}
