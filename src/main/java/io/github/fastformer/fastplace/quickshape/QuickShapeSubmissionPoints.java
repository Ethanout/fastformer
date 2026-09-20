package io.github.fastformer.fastplace.quickshape;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/** The Enter rule shared by authoritative drafts and captured client data. */
public final class QuickShapeSubmissionPoints {
   private QuickShapeSubmissionPoints() { }

   public static List<BlockPos> capture(List<BlockPos> confirmed, LineMode mode, BlockPos scrollOffset) {
      List<BlockPos> result = new ArrayList<>(confirmed.size() + 1);
      for (BlockPos point : confirmed) result.add(point.immutable());
      if (confirmed.size() == 1 && mode == LineMode.FREE_SCROLL) {
         BlockPos candidate = confirmed.getFirst().offset(scrollOffset).immutable();
         if (!candidate.equals(confirmed.getFirst())) result.add(candidate);
      }
      return List.copyOf(result);
   }
}
