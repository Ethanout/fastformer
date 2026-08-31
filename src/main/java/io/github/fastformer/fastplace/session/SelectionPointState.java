package io.github.fastformer.fastplace.session;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Owns the mutable point collection for one operation selection mode. */
final class SelectionPointState {
   BlockPos first;
   BlockPos second;
   final List<BlockPos> extraPoints = new ArrayList<>();
   int prismBasePointCount;
   int selectedPointIndex = -1;

   void clear() {
      this.first = null;
      this.second = null;
      this.extraPoints.clear();
      this.prismBasePointCount = 0;
      this.selectedPointIndex = -1;
   }

   PointStateSnapshot snapshot() {
      return new PointStateSnapshot(
         this.first,
         this.second,
         List.copyOf(this.extraPoints),
         this.prismBasePointCount
      );
   }

   void restore(PointStateSnapshot snapshot) {
      this.first = snapshot.first();
      this.second = snapshot.second();
      this.extraPoints.clear();
      this.extraPoints.addAll(snapshot.extraPoints());
      this.prismBasePointCount = snapshot.prismBasePointCount();
      this.selectedPointIndex = -1;
   }

   record PointStateSnapshot(
      BlockPos first,
      BlockPos second,
      List<BlockPos> extraPoints,
      int prismBasePointCount
   ) {
   }
}
