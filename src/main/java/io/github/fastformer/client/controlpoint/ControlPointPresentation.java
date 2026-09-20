package io.github.fastformer.client.controlpoint;

import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.geometry.ControlPoint;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import io.github.fastformer.fastplace.geometry.ControlPointFeedback;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Builds control-point display values from a snapshot and its resolved hover. */
public final class ControlPointPresentation {
   private ControlPointPresentation() {
   }

   public static List<ControlPoint> building(BuildingPreviewPayload snapshot, BlockPos candidate, BlockPos hoveredPoint) {
      if (snapshot.points().isEmpty() && candidate == null) {
         return List.of();
      }
      boolean closing = closingCandidate(snapshot, hoveredPoint);
      boolean closeable = snapshot.faceMode() == FaceMode.POLYGON
         && !snapshot.polygonClosed()
         && snapshot.points().size() >= 3;
      ArrayList<ControlPoint> result = new ArrayList<>(snapshot.points().size() + (candidate == null ? 0 : 1));
      if (!snapshot.points().isEmpty()) {
         result.add(closeable
            ? ControlPoint.primary(snapshot.points().getFirst(), closing)
            : ControlPoint.of(snapshot.points().getFirst(), ControlPointRole.PRIMARY));
         for (int i = 1; i < snapshot.points().size(); i++) {
            BlockPos point = snapshot.points().get(i);
            result.add(ControlPoint.secondary(point));
         }
      }
      if (candidate != null && !snapshot.polygonHeightConfirmed() && !closing && !snapshot.points().contains(candidate)) {
         ControlPointRole role = snapshot.points().isEmpty() ? ControlPointRole.PRIMARY : ControlPointRole.SECONDARY;
         result.add(ControlPoint.pending(Vec3.atCenterOf(candidate), role));
      }
      return List.copyOf(result);
   }

   public static List<ControlPoint> selection(
      OperationPreviewPayload snapshot, BlockPos candidate, boolean edgeInsertionHovered, int hoveredPointIndex
   ) {
      // Cuboid points are editing metadata only. The selection bounds are the
      // sole rendered authority, so drawing point1/point2 would create a
      // second frame and make an adjusted cuboid appear to have two sizes.
      if (snapshot.operationSelectionMode() == OperationSelectionMode.CUBOID) {
         return List.of();
      }
      List<BlockPos> points = snapshot.points();
      ArrayList<ControlPoint> result = new ArrayList<>(points.size() + (candidate == null ? 0 : 1));
      int pointIndex = 0;
      int hoveredIndex = snapshot.operationSelectionMode() == OperationSelectionMode.PRISM
         ? hoveredPointIndex
         : -1;
      if (snapshot.hasFirst() && pointIndex < points.size()) {
         boolean closeable = snapshot.operationSelectionMode() == OperationSelectionMode.PRISM
            && snapshot.operationPrismBasePointCount() == 0
            && points.size() >= 3;
         boolean hovered = closeable && hoveredPointIndex == pointIndex;
         ControlPoint first = closeable
            ? ControlPoint.primary(points.get(pointIndex), hovered)
            : ControlPoint.of(points.get(pointIndex), ControlPointRole.PRIMARY);
         if (hoveredIndex == pointIndex) {
            first = first.withFeedback(ControlPointFeedback.hoverable()).withHovered(true);
         }
         result.add(first);
         pointIndex++;
      }
      if (snapshot.hasSecond() && pointIndex < points.size()) {
         ControlPoint second = ControlPoint.secondary(points.get(pointIndex));
         if (hoveredIndex == pointIndex) {
            second = second.withFeedback(ControlPointFeedback.hoverable()).withHovered(true);
         }
         result.add(second);
         pointIndex++;
      }
      while (pointIndex < points.size()) {
         ControlPoint point = ControlPoint.secondary(points.get(pointIndex));
         if (hoveredIndex == pointIndex) {
            point = point.withFeedback(ControlPointFeedback.hoverable()).withHovered(true);
         }
         result.add(point);
         pointIndex++;
      }
      if (candidate != null && !points.contains(candidate)) {
         ControlPointRole role = snapshot.hasFirst() ? ControlPointRole.SECONDARY : ControlPointRole.PRIMARY;
         ControlPoint pending = ControlPoint.pending(Vec3.atCenterOf(candidate), role);
         result.add(edgeInsertionHovered
            ? pending.withFeedback(ControlPointFeedback.hoverable()).withHovered(true)
            : pending);
      }
      return List.copyOf(result);
   }

   public static Set<BlockPos> candidateBlocks(
      BuildingPreviewPayload snapshot, BlockPos candidate, BlockPos hoveredPoint
   ) {
      return candidate == null
         || snapshot.polygonHeightConfirmed()
         || snapshot.points().contains(candidate)
         || closingCandidate(snapshot, hoveredPoint)
         ? Set.of()
         : Set.of(candidate.immutable());
   }

   public static boolean closingCandidate(
      BuildingPreviewPayload snapshot, BlockPos hoveredPoint
   ) {
      return snapshot.faceMode() == FaceMode.POLYGON
         && !snapshot.polygonClosed()
         && snapshot.points().size() >= 3
         && snapshot.points().getFirst().equals(hoveredPoint);
   }

}
