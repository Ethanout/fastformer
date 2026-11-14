package io.github.fastformer.common.selection.session;

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

   List<BlockPos> points() {
      List<BlockPos> points = new ArrayList<>();
      if (first != null) points.add(first);
      if (second != null) points.add(second);
      points.addAll(extraPoints);
      return List.copyOf(points);
   }

   void set(int index, BlockPos point) {
      BlockPos immutable = point.immutable();
      if (index == 0) first = immutable;
      else if (index == 1) second = immutable;
      else extraPoints.set(index - 2, immutable);
   }

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
