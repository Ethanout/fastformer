package io.github.fastformer.client.quickshape.pointer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.quickshape.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class QuickShapePointerTest {
   @Test void allModesKeepPriorModifierResolution() {
      for (var line : LineMode.values()) for (var face : FaceMode.values()) for (var volume : VolumeMode.values())
         for (int count = 0; count <= 4; count++) for (boolean closed : List.of(false, true)) for (boolean alt : List.of(false, true)) {
            var points = java.util.stream.IntStream.range(0, count).mapToObj(i -> new BlockPos(i,0,0)).toList();
            var p = snapshot(points, line, face, volume, closed, false);
            var expected = p.modes().withModifierHeld(alt);
            var stage = QuickShapeStage.resolve(count, face, closed);
            if (face != FaceMode.POLYGON && alt && (stage == QuickShapeStage.FACE || stage == QuickShapeStage.VOLUME))
               expected = expected.withFaceTieBias(LineTieBias.OPPOSITE);
            if (stage == QuickShapeStage.POINT && p.pointMode() == PointMode.RAYCAST && points.isEmpty()
               || stage == QuickShapeStage.LINE && line == LineMode.RAYCAST)
               expected = expected.withRaycastPlacement(alt ? RaycastPlacement.EMBEDDED : RaycastPlacement.SURFACE);
            assertEquals(expected, QuickShapeModes.effective(p, alt));
         }
   }

   @Test void candidateMatchesExistingGeometryForBlockAndMissRays() {
      var eye = new Vec3(0.5, 2.5, -5);
      var view = new Vec3(0,0,1);
      var block = new BlockHitResult(new Vec3(2,2,5), Direction.NORTH, new BlockPos(2,2,5), false);
      var miss = BlockHitResult.miss(new Vec3(0.5,2.5,20), Direction.NORTH, new BlockPos(0,2,20));
      for (var line : LineMode.values()) for (var face : FaceMode.values()) for (var hit : List.of(block, miss))
         for (boolean modifier : List.of(false, true)) {
            var p = snapshot(List.of(BlockPos.ZERO), line, face, VolumeMode.PERPENDICULAR_TO_FACE, false, false);
            var hitBlock = hit.getType() == Type.BLOCK ? hit.getBlockPos() : p.points().getFirst().offset(p.freeScrollOffset());
            var surface = hit.getType() == Type.BLOCK ? hitBlock.relative(hit.getDirection()) : hitBlock;
            var expected = FastPlaceGeometry.resolveCandidate(p.points(), p.polygonClosed(), hitBlock, surface,
               p.faceBaseOffset(), p.volumeBaseOffset(), p.perpendicularAnchor(), eye, view, p.freeScrollOffset(), QuickShapeModes.effective(p, modifier));
            assertEquals(expected, QuickShapePointer.resolveCandidate(p, eye, view, hit, modifier));
         }
   }

   @Test void missingContextCannotProduceCandidatesAndOwnersKeepIndependentPaths() {
      var a = new AtomicReference<>(snapshot(List.of(BlockPos.ZERO), LineMode.AXIS, FaceMode.POLYGON, VolumeMode.PERPENDICULAR_TO_FACE, false, false));
      var b = new AtomicReference<>(BuildingPreviewSnapshot.inactive());
      var first = new QuickShapePointer(a::get, () -> null, ignored -> { throw new AssertionError(); });
      var second = new QuickShapePointer(b::get, () -> null, ignored -> { throw new AssertionError(); });
      assertEquals(List.of(BlockPos.ZERO), first.openPath());
      assertTrue(second.openPath().isEmpty());
      assertNull(first.candidate());
      assertNull(first.lineCandidate());
      a.set(BuildingPreviewSnapshot.inactive());
      assertTrue(first.openPath().isEmpty());
   }

   @Test void fixedPolygonHeightNeverUsesNewRayForTheCandidate() {
      var p = snapshot(List.of(BlockPos.ZERO, new BlockPos(4,5,6)), LineMode.AXIS, FaceMode.POLYGON, VolumeMode.PERPENDICULAR_TO_FACE, true, true);
      var hit = BlockHitResult.miss(new Vec3(20,20,20), Direction.UP, new BlockPos(20,20,20));
      assertEquals(new BlockPos(4,5,6), QuickShapePointer.resolveCandidate(p, Vec3.ZERO, new Vec3(0,0,1), hit, false));
   }

   private static BuildingPreviewSnapshot snapshot(List<BlockPos> points, LineMode line, FaceMode face, VolumeMode volume, boolean closed, boolean height) {
      var p = BuildingPreviewSnapshot.inactive();
      return new BuildingPreviewSnapshot(true, true, true, false, closed, height, p.polygonVolumeShape(), points, p.angleDistance(),
         new BlockPos(1,2,3), p.faceBaseOffset(), p.volumeBaseOffset(), p.perpendicularAnchor(), p.angleDegrees(), p.pointMode(),
         p.raycastPlacement(), line, face, volume, p.fillMode(), p.faceTieBias(), null);
   }
}
