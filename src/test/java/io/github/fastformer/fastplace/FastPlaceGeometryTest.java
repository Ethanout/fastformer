package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FastPlaceGeometryTest {
   @Test
   void quickRaycastDefaultUsesTheHitSurface() {
      FastPlaceSettings settings = FastPlaceSettings.fromTag(new net.minecraft.nbt.CompoundTag());
      FastPlaceSession session = new FastPlaceSession();
      FastPlaceGeometry.Modes modes = FastPlaceManager.effectiveModes(settings, session);

      assertEquals(RaycastPlacement.SURFACE, modes.raycastPlacement());
      assertEquals(
         new BlockPos(4, 5, 7),
         FastPlaceGeometry.resolveCandidate(
            List.of(),
            false,
            new BlockPos(4, 5, 6),
            new BlockPos(4, 5, 7),
            Vec3.ZERO,
            Vec3.ZERO,
            null,
            Vec3.ZERO,
            new Vec3(0.0, 0.0, 1.0),
            BlockPos.ZERO,
            modes
         )
      );
   }

   @Test
   void freeScrollEntryKeepsTheLatestAxisCandidate() {
      BlockPos first = new BlockPos(10, 20, 30);
      List<BlockPos> points = List.of(first);
      Vec3 eye = new Vec3(2.5, 24.5, 36.5);
      Vec3 view = new Vec3(1.0, -0.1, -0.2).normalize();
      BlockPos axisCandidate = candidate(points, eye, view, BlockPos.ZERO, LineMode.AXIS);
      BlockPos inheritedOffset = axisCandidate.subtract(first);

      BlockPos freeScrollCandidate = candidate(points, eye, view, inheritedOffset, LineMode.FREE_SCROLL);

      assertEquals(axisCandidate, freeScrollCandidate);
   }

   @Test
   void inheritedFreeScrollOffsetSurvivesTheModeChangeHook() {
      FastPlaceSession session = new FastPlaceSession();
      BlockPos inheritedOffset = new BlockPos(7, -2, 4);
      session.setFreeScrollOffset(inheritedOffset);

      session.onModeChanged();

      assertEquals(inheritedOffset, session.freeScrollOffset());
   }

   @Test
   void tiltedQuickFormCuboidUsesOwnedWorkerSolidAndSurface() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(3, 3, 0),
         new BlockPos(-1, 1, 4),
         new BlockPos(5, -5, 7)
      );

      Set<BlockPos> solid = FastPlaceGeometry.blocks(points, modes(FillMode.SOLID));
      Set<BlockPos> hollow = FastPlaceGeometry.blocks(points, modes(FillMode.HOLLOW));
      Set<BlockPos> outline = FastPlaceGeometry.blocks(points, modes(FillMode.OUTLINE));

      assertTrue(solid.size() > hollow.size());
      assertEquals(boundary(solid), hollow);
      assertTrue(solid.containsAll(outline));
   }

   @Test
   void polygonPointsAfterThePlaneIsKnownStayOnThatPlane() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(4, 4, 0),
         new BlockPos(0, 0, 4)
      );
      FastPlaceGeometry.Modes modes = new FastPlaceGeometry.Modes(
         PointMode.RAYCAST,
         RaycastPlacement.EMBEDDED,
         LineMode.RAYCAST,
         FaceMode.POLYGON,
         VolumeMode.PERPENDICULAR_TO_FACE,
         FillMode.SOLID,
         0.0,
         false
      );
      Vec3 eye = new Vec3(3.5, 10.5, 2.5);

      BlockPos candidate = FastPlaceGeometry.resolveCandidate(
         points,
         false,
         new BlockPos(3, 10, 2),
         new BlockPos(3, 10, 2),
         Vec3.ZERO,
         Vec3.ZERO,
         null,
         eye,
         new Vec3(0.0, -1.0, 0.0),
         BlockPos.ZERO,
         modes
      );

      assertEquals(new BlockPos(3, 3, 2), candidate);
      Set<BlockPos> face = FastPlaceGeometry.blocks(
         List.of(points.get(0), points.get(1), points.get(2), candidate),
         modes
      );
      assertTrue(
         face.stream().allMatch(position -> Math.abs(position.getX() - position.getY()) <= 1),
         "six-neighbor bridge voxels must remain immediately adjacent to the confirmed plane"
      );
   }

   @Test
   void perpendicularVolumeHeightFollowsIncreasingAndNegativeAxisTargets() {
      int previousHeight = Integer.MIN_VALUE;
      for (int height : new int[]{0, 12, 48, 96}) {
         BlockPos candidate = perpendicularVolumeCandidate(height);
         assertEquals(new BlockPos(4, height, 0), candidate);
         assertTrue(candidate.getY() > previousHeight);
         previousHeight = candidate.getY();
      }

      assertEquals(new BlockPos(4, -12, 0), perpendicularVolumeCandidate(-12));
      assertEquals(new BlockPos(4, -48, 0), perpendicularVolumeCandidate(-48));
   }

   @Test
   void perpendicularVolumeHeightStaysFiniteNearParallelView() {
      BlockPos vertical = perpendicularVolumeCandidate(new Vec3(0.0, 1.0, 0.0));
      BlockPos nearVertical = perpendicularVolumeCandidate(new Vec3(1.0E-9, 1.0, 0.0).normalize());

      assertTrue(Math.abs(vertical.getY() - nearVertical.getY()) <= 1);
      assertTrue(Math.abs(vertical.getY()) <= 130);
   }

   @Test
   void perpendicularVolumeGenerationIgnoresGridSnapLateralOffset() {
      List<BlockPos> base = List.of(
         new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(0, 0, 4)
      );
      Set<BlockPos> snapped = FastPlaceGeometry.blocks(
         List.of(base.get(0), base.get(1), base.get(2), new BlockPos(2, 3, 1)), modes(FillMode.OUTLINE)
      );
      Set<BlockPos> normal = FastPlaceGeometry.blocks(
         List.of(base.get(0), base.get(1), base.get(2), new BlockPos(0, 3, 4)), modes(FillMode.OUTLINE)
      );

      assertEquals(normal, snapped);
   }

   private static BlockPos candidate(
      List<BlockPos> points, Vec3 eye, Vec3 view, BlockPos freeScrollOffset, LineMode lineMode
   ) {
      FastPlaceGeometry.Modes modes = new FastPlaceGeometry.Modes(
         PointMode.RAYCAST,
         RaycastPlacement.EMBEDDED,
         lineMode,
         FaceMode.POLYGON,
         VolumeMode.PERPENDICULAR_TO_FACE,
         FillMode.OUTLINE,
         0.0,
         false
      );
      return FastPlaceGeometry.resolveCandidate(
         points,
         false,
         new BlockPos(18, 23, 34),
         new BlockPos(18, 23, 34),
         Vec3.ZERO,
         Vec3.ZERO,
         null,
         eye,
         view,
         freeScrollOffset,
         modes
      );
   }

   private static BlockPos perpendicularVolumeCandidate(int height) {
      List<BlockPos> points = volumePoints();
      Vec3 eye = new Vec3(-10.5, 2.5, 0.5);
      Vec3 target = Vec3.atCenterOf(points.get(2)).add(0.0, height, 0.0);
      return perpendicularVolumeCandidate(target.subtract(eye).normalize());
   }

   private static BlockPos perpendicularVolumeCandidate(Vec3 view) {
      List<BlockPos> points = volumePoints();
      return FastPlaceGeometry.resolveCandidate(
         points,
         false,
         BlockPos.ZERO,
         BlockPos.ZERO,
         Vec3.ZERO,
         Vec3.ZERO,
         points.get(2),
         new Vec3(-10.5, 2.5, 0.5),
         view,
         BlockPos.ZERO,
         modes(FillMode.OUTLINE)
      );
   }

   private static List<BlockPos> volumePoints() {
      return List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(0, 0, 4),
         new BlockPos(4, 0, 0)
      );
   }

   private static FastPlaceGeometry.Modes modes(FillMode fillMode) {
      return new FastPlaceGeometry.Modes(
         PointMode.RAYCAST,
         RaycastPlacement.EMBEDDED,
         LineMode.AXIS,
         FaceMode.COORDINATE_PLANE,
         VolumeMode.PERPENDICULAR_TO_FACE,
         fillMode,
         0.0,
         false
      );
   }

   private static Set<BlockPos> boundary(Set<BlockPos> solid) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (BlockPos position : solid) {
         for (Direction direction : Direction.values()) {
            if (!solid.contains(position.relative(direction))) {
               result.add(position);
               break;
            }
         }
      }
      return result;
   }
}
