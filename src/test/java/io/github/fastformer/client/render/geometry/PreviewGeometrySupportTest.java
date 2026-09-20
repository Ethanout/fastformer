package io.github.fastformer.client.render.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GuideLine;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PreviewGeometrySupportTest {
   @Test
   void unionAndDifferencePreserveEmptyInputs() {
      BlockPos first = new BlockPos(1, 2, 3);
      BlockPos second = new BlockPos(4, 5, 6);

      assertEquals(Set.of(first, second), PreviewGeometrySupport.unionBlocks(Set.of(first), Set.of(second)));
      assertEquals(Set.of(first), PreviewGeometrySupport.withoutBlocks(Set.of(first, second), Set.of(second)));
      assertEquals(Set.of(first), PreviewGeometrySupport.unionBlocks(Set.of(first), Set.of()));
      Set<BlockPos> empty = Set.of();
      assertSame(empty, PreviewGeometrySupport.withoutBlocks(empty, Set.of(second)));
   }

   @Test
   void localAxisRotationUsesEulerRotationOrder() {
      Vec3 rotated = PreviewGeometrySupport.rotateLocalAxis(
         AxisGizmo.Axis.X,
         new Vec3(0.0, 0.0, Math.PI * 0.5)
      );

      assertEquals(0.0, rotated.x, 1.0E-9);
      assertEquals(1.0, rotated.y, 1.0E-9);
      assertEquals(0.0, rotated.z, 1.0E-9);
   }

   @Test
   void polygonOpenPathDoesNotAddClosingEdge() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(4, 0, 3)
      );

      List<GuideLine> edges = PreviewGeometrySupport.outlineGeometryEdges(
         points, FaceMode.POLYGON, false, false, PolygonVolumeShape.EXTRUDE
      );

      assertEquals(List.of(line(points.get(0), points.get(1)), line(points.get(1), points.get(2))), edges);
   }

   @Test
   void polygonClosedFaceConnectsLastBasePointToFirst() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(4, 0, 3)
      );

      List<GuideLine> edges = PreviewGeometrySupport.outlineGeometryEdges(
         points, FaceMode.POLYGON, true, false, PolygonVolumeShape.EXTRUDE
      );

      assertEquals(List.of(
         line(points.get(0), points.get(1)), line(points.get(1), points.get(2)), line(points.get(2), points.get(0))
      ), edges);
   }

   @Test
   void polygonExtrusionUsesLastPointOnlyAsHeightPoint() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(1_000_000, 0, 0),
         new BlockPos(1_000_000, 0, 1_000_000),
         new BlockPos(1_000_000, 2_000_000, 1_000_000)
      );

      List<GuideLine> edges = PreviewGeometrySupport.outlineGeometryEdges(
         points, FaceMode.POLYGON, true, true, PolygonVolumeShape.EXTRUDE
      );

      assertEquals(9, edges.size());
      assertEquals(line(points.get(2), points.get(0)), edges.get(6));
      assertEquals(
         new GuideLine(Vec3.atCenterOf(points.get(0)), Vec3.atCenterOf(points.get(0)).add(0, 2_000_000, 0)),
         edges.get(2)
      );
   }

   @Test
   void polygonApexProjectsHeightOntoBaseNormal() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(4, 0, 3), new BlockPos(9, 5, 8)
      );

      List<GuideLine> edges = PreviewGeometrySupport.outlineGeometryEdges(
         points, FaceMode.POLYGON, true, true, PolygonVolumeShape.APEX
      );
      Vec3 apex = new Vec3(19.0 / 6.0, 5.5, 1.5);

      assertEquals(6, edges.size());
      assertEquals(new GuideLine(Vec3.atCenterOf(points.get(0)), apex), edges.get(3));
      assertEquals(new GuideLine(Vec3.atCenterOf(points.get(2)), apex), edges.get(5));
   }

   @Test
   void degenerateFaceRetainsItsConfirmedInitialLine() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(2, 0, 0)
      );

      List<GuideLine> edges = PreviewGeometrySupport.outlineGeometryEdges(
         points, FaceMode.COORDINATE_PLANE, false, false, PolygonVolumeShape.EXTRUDE, VolumeMode.PERPENDICULAR_TO_FACE
      );

      assertEquals(List.of(line(points.getFirst(), points.get(1))), edges);
   }

   @Test
   void perpendicularVolumeOutlineIgnoresGridSnapLateralOffset() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(4, 0, 0),
         new BlockPos(0, 0, 4),
         new BlockPos(2, 3, 1)
      );

      List<GuideLine> edges = PreviewGeometrySupport.outlineGeometryEdges(
         points, FaceMode.COORDINATE_PLANE, false, false, PolygonVolumeShape.EXTRUDE, VolumeMode.PERPENDICULAR_TO_FACE
      );

      assertEquals(12, edges.size());
      assertTrue(edges.contains(new GuideLine(
         Vec3.atCenterOf(points.getFirst()), Vec3.atCenterOf(points.getFirst()).add(0.0, 3.0, 0.0)
      )));
      assertFalse(edges.contains(new GuideLine(
         Vec3.atCenterOf(points.getFirst()), Vec3.atCenterOf(points.getFirst()).add(2.0, 3.0, 1.0)
      )));
   }

   private static GuideLine line(BlockPos from, BlockPos to) {
      return new GuideLine(Vec3.atCenterOf(from), Vec3.atCenterOf(to));
   }
}
