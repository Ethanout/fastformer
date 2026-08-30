package io.github.fastformer.client.operation;

import io.github.fastformer.fastplace.OperationSelectionMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Owns the mutable client-side selection gesture data behind one state
 * machine. The controller exposes only the derived four-state projection.
 */
final class ClientSelectionSession {
   private final ArrayList<BlockPos> draftPoints = new ArrayList<>();
   private BlockPos draftMinPoint;
   private BlockPos draftMaxPoint;
   private OperationSelectionMode selectionMode = OperationSelectionMode.CUBOID;
   private int prismBaseCount;
   private boolean altHeld;
   private ClientSelectionState state = ClientSelectionState.UNFOCUSED;

   OperationSelectionMode selectionMode() {
      return this.selectionMode;
   }

   void setSelectionMode(OperationSelectionMode mode) {
      if (mode != null) {
         this.selectionMode = mode;
      }
   }

   boolean altHeld() {
      return this.altHeld;
   }

   void setAltHeld(boolean held) {
      this.altHeld = held;
   }

   ClientSelectionState state() {
      return this.state;
   }

   void refresh(boolean hasSelection, boolean workspaceEmpty, boolean serverPointing) {
      this.state = this.altHeld
         ? ClientSelectionState.ALT_FOCUSED
         : !this.draftPoints.isEmpty() || workspaceEmpty && serverPointing
            ? ClientSelectionState.POINTING
            : hasSelection ? ClientSelectionState.FOCUSED : ClientSelectionState.UNFOCUSED;
   }

   boolean hasDraft() {
      return !this.draftPoints.isEmpty();
   }

   int draftSize() {
      return this.draftPoints.size();
   }

   BlockPos draftFirst() {
      return this.draftPoints.isEmpty() ? null : this.draftPoints.getFirst();
   }

   BlockPos draftAt(int index) {
      return this.draftPoints.get(index);
   }

   List<BlockPos> draftPoints() {
      return List.copyOf(this.draftPoints);
   }

   List<BlockPos> selectionDraftPoints() {
      if (this.draftPoints.size() >= 2 && this.draftMinPoint != null && this.draftMaxPoint != null) {
         return List.of(this.draftMinPoint, this.draftMaxPoint);
      }
      return draftPoints();
   }

   void addDraftPoint(BlockPos point) {
      if (point != null) {
         this.draftPoints.add(point.immutable());
         refreshDraftBounds();
      }
   }

   void setDraftPoint(int index, BlockPos point) {
      if (point != null && index >= 0 && index < this.draftPoints.size()) {
         this.draftPoints.set(index, point.immutable());
         refreshDraftBounds();
      }
   }

   /** Expands the cuboid draft without changing its input points. */
   boolean expandDraftTo(BlockPos point) {
      if (point == null || this.draftPoints.size() < 2) {
         return false;
      }
      BlockPos nextMin = new BlockPos(
         Math.min(this.draftMinPoint.getX(), point.getX()),
         Math.min(this.draftMinPoint.getY(), point.getY()),
         Math.min(this.draftMinPoint.getZ(), point.getZ())
      );
      BlockPos nextMax = new BlockPos(
         Math.max(this.draftMaxPoint.getX(), point.getX()),
         Math.max(this.draftMaxPoint.getY(), point.getY()),
         Math.max(this.draftMaxPoint.getZ(), point.getZ())
      );
      if (nextMin.equals(this.draftMinPoint) && nextMax.equals(this.draftMaxPoint)) {
         return false;
      }
      this.draftMinPoint = nextMin;
      this.draftMaxPoint = nextMax;
      return true;
   }

   BlockPos draftMinPoint() {
      return this.draftMinPoint;
   }

   BlockPos draftMaxPoint() {
      return this.draftMaxPoint;
   }

   BlockPos removeLastDraftPoint() {
      return this.draftPoints.isEmpty() ? null : this.draftPoints.removeLast();
   }

   void clearDraft() {
      this.draftPoints.clear();
      this.prismBaseCount = 0;
      this.draftMinPoint = null;
      this.draftMaxPoint = null;
   }

   int prismBaseCount() {
      return this.prismBaseCount;
   }

   void setPrismBaseCount(int count) {
      this.prismBaseCount = Math.max(0, count);
   }

   void restoreDraft(List<BlockPos> points, int baseCount) {
      this.draftPoints.clear();
      if (points != null) {
         points.stream().filter(java.util.Objects::nonNull)
            .map(BlockPos::immutable).forEach(this.draftPoints::add);
      }
      this.prismBaseCount = Math.max(0, baseCount);
      refreshDraftBounds();
   }

   void restoreDraftBounds(BlockPos min, BlockPos max) {
      if (min != null && max != null && this.draftPoints.size() >= 2) {
         this.draftMinPoint = min.immutable();
         this.draftMaxPoint = max.immutable();
      }
   }

   private void refreshDraftBounds() {
      if (this.draftPoints.isEmpty()) {
         this.draftMinPoint = null;
         this.draftMaxPoint = null;
         return;
      }
      BlockPos min = this.draftPoints.getFirst();
      BlockPos max = min;
      for (BlockPos point : this.draftPoints) {
         min = new BlockPos(
            Math.min(min.getX(), point.getX()),
            Math.min(min.getY(), point.getY()),
            Math.min(min.getZ(), point.getZ())
         );
         max = new BlockPos(
            Math.max(max.getX(), point.getX()),
            Math.max(max.getY(), point.getY()),
            Math.max(max.getZ(), point.getZ())
         );
      }
      this.draftMinPoint = min;
      this.draftMaxPoint = max;
   }
}
