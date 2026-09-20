package io.github.fastformer.fastplace.placement.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class PlacementBlockEstimateTest {
   private static final int DEFAULT_LIMIT = 20_972_152;

   @Test
   void lineEstimateMatchesOneThreeAndSixteenBlockResults() {
      assertLineEstimate(1);
      assertLineEstimate(3);
      assertLineEstimate(16);
   }

   @Test
   void diagonalLineUsesItsOutputLengthInsteadOfBoundingVolume() {
      List<BlockPos> points = List.of(BlockPos.ZERO, new BlockPos(15, 15, 15));

      assertEquals(16L, estimate(points, FillMode.OUTLINE, DEFAULT_LIMIT));
      assertEquals(16L, estimate(points, FillMode.SOLID, DEFAULT_LIMIT));
   }

   @Test
   void ordinaryOutlineFaceEstimateIsSmallerThanSolidEstimateAndStillCoversOutput() {
      List<BlockPos> points = List.of(
         BlockPos.ZERO,
         new BlockPos(15, 0, 0),
         new BlockPos(0, 15, 15)
      );
      FastPlaceGeometry.Modes outline = modes(FillMode.OUTLINE);
      FastPlaceGeometry.Modes solid = modes(FillMode.SOLID);

      long outlineEstimate = estimate(points, outline, false, PolygonVolumeShape.EXTRUDE, DEFAULT_LIMIT);
      long solidEstimate = estimate(points, solid, false, PolygonVolumeShape.EXTRUDE, DEFAULT_LIMIT);
      int actualOutline = FastPlaceGeometry.blocks(
         points, outline, false, PolygonVolumeShape.EXTRUDE, DEFAULT_LIMIT
      ).size();

      assertTrue(outlineEstimate >= actualOutline);
      assertTrue(outlineEstimate < solidEstimate);
   }

   @Test
   void ordinaryOutlinePrismEstimateCoversGeneratedWireframe() {
      List<BlockPos> points = List.of(
         BlockPos.ZERO,
         new BlockPos(7, 0, 0),
         new BlockPos(0, 7, 3),
         new BlockPos(0, 11, 3)
      );
      FastPlaceGeometry.Modes outline = modes(FillMode.OUTLINE);

      long estimate = estimate(points, outline, false, PolygonVolumeShape.EXTRUDE, DEFAULT_LIMIT);
      int actual = FastPlaceGeometry.blocks(
         points, outline, false, PolygonVolumeShape.EXTRUDE, DEFAULT_LIMIT
      ).size();

      assertTrue(estimate >= actual);
   }

   @Test
   void ordinarySolidPrismEstimateCoversGeneratedBlocks() {
      List<BlockPos> points = List.of(
         BlockPos.ZERO,
         new BlockPos(3, 0, 0),
         new BlockPos(0, 3, 1),
         new BlockPos(0, 5, 1)
      );
      FastPlaceGeometry.Modes solid = modes(FillMode.SOLID);

      long estimate = estimate(points, solid, false, PolygonVolumeShape.EXTRUDE, DEFAULT_LIMIT);
      int actual = FastPlaceGeometry.blocks(
         points, solid, false, PolygonVolumeShape.EXTRUDE, DEFAULT_LIMIT
      ).size();

      assertTrue(estimate >= actual);
   }

   @Test
   void polygonVolumeEstimateIncludesTheDerivedTopGeometry() {
      List<BlockPos> points = List.of(
         BlockPos.ZERO,
         new BlockPos(4, 0, 0),
         new BlockPos(4, 0, 4),
         new BlockPos(0, 0, 4),
         new BlockPos(9, 3, 9)
      );
      FastPlaceGeometry.Modes solid = modes(FillMode.SOLID, FaceMode.POLYGON);

      for (PolygonVolumeShape shape : PolygonVolumeShape.values()) {
         long estimate = estimate(points, solid, true, shape, DEFAULT_LIMIT);
         int actual = FastPlaceGeometry.blocks(points, solid, true, shape, DEFAULT_LIMIT).size();
         assertTrue(estimate >= actual, shape.name());
      }
   }

   @Test
   void estimateNeverExceedsTheActualGenerationLimit() {
      List<BlockPos> points = List.of(
         new BlockPos(Integer.MIN_VALUE, -64, Integer.MIN_VALUE),
         new BlockPos(Integer.MAX_VALUE, 319, Integer.MAX_VALUE),
         new BlockPos(Integer.MIN_VALUE, 319, Integer.MAX_VALUE)
      );

      assertEquals(17L, estimate(points, FillMode.SOLID, 16));
   }

   private static void assertLineEstimate(int blocks) {
      List<BlockPos> points = blocks == 1
         ? List.of(BlockPos.ZERO)
         : List.of(BlockPos.ZERO, new BlockPos(blocks - 1, 0, 0));
      assertEquals(blocks, estimate(points, FillMode.OUTLINE, DEFAULT_LIMIT));
   }

   private static long estimate(List<BlockPos> points, FillMode fillMode, int maxPlacement) {
      return estimate(points, modes(fillMode), false, PolygonVolumeShape.EXTRUDE, maxPlacement);
   }

   private static long estimate(
      List<BlockPos> points,
      FastPlaceGeometry.Modes modes,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape polygonVolumeShape,
      int maxPlacement
   ) {
      return PlacementBlockEstimate.upperBound(
         points, modes, polygonHeightConfirmed, polygonVolumeShape, maxPlacement
      );
   }

   private static FastPlaceGeometry.Modes modes(FillMode fillMode) {
      return modes(fillMode, FaceMode.COORDINATE_PLANE);
   }

   private static FastPlaceGeometry.Modes modes(FillMode fillMode, FaceMode faceMode) {
      return new FastPlaceGeometry.Modes(
         PointMode.RAYCAST,
         RaycastPlacement.SURFACE,
         LineMode.AXIS,
         faceMode,
         VolumeMode.FREE,
         fillMode,
         0.0,
         false
      );
   }
}
