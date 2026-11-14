package io.github.fastformer.client.preview.geometry;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.geometry.GeometryHit;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryPreviewModelsTest {
   @Test
   void keyIncludesRevisionModifierEyeViewCandidateAndHoveredPoint() {
      var calls = new AtomicInteger();
      var cache = new GeometryPreviewModels((message, failure) -> fail(message, failure), input -> {
         calls.incrementAndGet();
         return GeometryPreviewPlan.controlPoints(input.snapshot().points(), input.hoveredPoint());
      });
      var snapshot = snapshot(List.of(BlockPos.ZERO));
      var first = input(snapshot, 1, false, Vec3.ZERO, new Vec3(0,0,1), null, null);
      var model = cache.build(first);
      assertSame(model, cache.build(first));
      assertEquals(1, calls.get());
      cache.build(input(snapshot, 2, false, Vec3.ZERO, new Vec3(0,0,1), null, null));
      cache.build(input(snapshot, 2, true, Vec3.ZERO, new Vec3(0,0,1), null, null));
      cache.build(input(snapshot, 2, true, new Vec3(1,0,0), new Vec3(0,0,1), null, null));
      cache.build(input(snapshot, 2, true, new Vec3(1,0,0), new Vec3(1,0,0), null, null));
      var candidate = GeometryHit.point(new BlockPos(2,0,0));
      cache.build(input(snapshot, 2, true, new Vec3(1,0,0), new Vec3(1,0,0), candidate, null));
      cache.build(input(snapshot, 2, true, new Vec3(1,0,0), new Vec3(1,0,0), candidate, BlockPos.ZERO));
      assertEquals(7, calls.get());
      cache.clear();
      cache.build(first);
      assertEquals(8, calls.get());
   }

   @Test
   void excludesSemanticMarkerCellsFromBothShellLayers() {
      var marker = BlockPos.ZERO;
      var confirmed = new BlockPos(1,0,0);
      var pending = new BlockPos(2,0,0);
      var plan = GeometryPreviewPlan.builder(List.of(marker), null)
         .blocks(new io.github.fastformer.fastplace.geometry.GeometryPreviewBlocks.Layers(Set.of(marker, confirmed), Set.of(marker, pending))).build();
      var cache = new GeometryPreviewModels((message, failure) -> fail(message, failure), input -> plan);
      var model = cache.build(input(snapshot(List.of(marker)), 1, false, Vec3.ZERO, new Vec3(0,0,1), null, null));
      assertEquals(Set.of(confirmed), model.blocks().confirmed());
      assertEquals(Set.of(pending), model.blocks().pending());
      assertSame(plan, model.plan());
   }

   @Test
   void failurePublishesControlPointsOnceAndNewRevisionCanRecover() {
      var failure = new IllegalStateException("generation failed");
      var failures = new ArrayList<Throwable>();
      var calls = new AtomicInteger();
      var cache = new GeometryPreviewModels((message, error) -> failures.add(error), input -> {
         if (calls.incrementAndGet() == 1) throw failure;
         return GeometryPreviewPlan.builder(input.snapshot().points(), null).ghost(Set.of(new BlockPos(3,0,0))).build();
      });
      var snapshot = snapshot(List.of(BlockPos.ZERO));
      var first = input(snapshot, 1, false, Vec3.ZERO, new Vec3(0,0,1), null, null);
      var fallback = cache.build(first);
      assertEquals(1, fallback.plan().controlPoints().size());
      assertTrue(fallback.blocks().confirmed().isEmpty());
      assertSame(fallback, cache.build(first));
      assertEquals(List.of(failure), failures);
      assertEquals(Set.of(new BlockPos(3,0,0)), cache.build(input(snapshot, 2, false, Vec3.ZERO, new Vec3(0,0,1), null, null)).blocks().confirmed());
      assertEquals(2, calls.get());
   }

   @Test
   void independentOwnersDoNotReuseEachOthersPlan() {
      var firstPlan = GeometryPreviewPlan.builder(List.of(), null).ghost(Set.of(BlockPos.ZERO)).build();
      var secondPlan = GeometryPreviewPlan.builder(List.of(), null).ghost(Set.of(new BlockPos(5,0,0))).build();
      var first = new GeometryPreviewModels((message, failure) -> fail(), input -> firstPlan);
      var second = new GeometryPreviewModels((message, failure) -> fail(), input -> secondPlan);
      var input = input(snapshot(List.of()), 1, false, Vec3.ZERO, new Vec3(0,0,1), null, null);
      assertSame(firstPlan, first.build(input).plan());
      assertSame(secondPlan, second.build(input).plan());
      first.clear();
      assertSame(secondPlan, second.build(input).plan());
   }

   private static GeometryPreviewInput input(GeometryPreviewPayload snapshot, long version, boolean modifier,
      Vec3 eye, Vec3 view, GeometryHit candidate, BlockPos hovered) {
      return new GeometryPreviewInput(snapshot, version, modifier, eye, view, candidate, hovered);
   }

   private static GeometryPreviewPayload snapshot(List<BlockPos> points) {
      var p = GeometryPreviewPayload.inactive();
      return new GeometryPreviewPayload(true, GeometryMode.WALL, points, null, null, false, false,
         BlockPos.ZERO, p.polyhedronShapeVariant(), p.coneShapeVariant(), p.compoundShapeVariant(), p.polyhedronSizeMode(),
         p.fillMode(), p.conePlaneMode(), p.coneRadius(), p.coneScaleX(), p.coneScaleZ(), p.coneTopScaleOffset(), p.coneTopOffset(),
         p.coneRotationRadians(), p.coneGizmoLocal(), p.rotation(), p.polyhedronLocalScale(), p.polyhedronWorldScale(),
         p.polyhedronGizmoLocal(), p.selectedPointIndex());
   }
}
