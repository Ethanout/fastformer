package io.github.fastformer.client.geometry.pointer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.geometry.GeometryHit;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryPlanCacheTest {
   @Test void allCapturedFactsInvalidateTheSharedPlan() {
      var calls = new AtomicInteger();
      var cache = new GeometryPlanCache((message, failure) -> fail(message, failure), input -> {
         calls.incrementAndGet();
         return GeometryPreviewPlan.controlPoints(input.snapshot().points(), input.hoveredPoint());
      });
      var p = GeometryPreviewPayload.inactive();
      var z = new Vec3(0,0,1);
      var x = new Vec3(1,0,0);
      var first = new GeometryPlanInput(p, 1, false, Vec3.ZERO, z, null, null);
      assertSame(cache.build(first), cache.build(first));
      cache.build(new GeometryPlanInput(p, 2, false, Vec3.ZERO, z, null, null));
      cache.build(new GeometryPlanInput(p, 2, true, Vec3.ZERO, z, null, null));
      cache.build(new GeometryPlanInput(p, 2, true, x, z, null, null));
      cache.build(new GeometryPlanInput(p, 2, true, x, x, null, null));
      var candidate = GeometryHit.point(new BlockPos(2,0,0));
      cache.build(new GeometryPlanInput(p, 2, true, x, x, candidate, null));
      cache.build(new GeometryPlanInput(p, 2, true, x, x, candidate, BlockPos.ZERO));
      assertEquals(7, calls.get());
      cache.clear();
      cache.build(first);
      cache.build(new GeometryPlanInput(p.withRevision(2), 1, false, Vec3.ZERO, z, null, null));
      assertEquals(9, calls.get());
   }

   @Test void failureFallsBackOnceAndNewRevisionRecovers() {
      var failures = new ArrayList<Throwable>();
      var calls = new AtomicInteger();
      var cache = new GeometryPlanCache((message, error) -> failures.add(error), input -> {
         if (calls.incrementAndGet() == 1) throw new IllegalStateException("generation failed");
         return GeometryPreviewPlan.builder(List.of(), null).ghost(Set.of(BlockPos.ZERO)).build();
      });
      var p = GeometryPreviewPayload.inactive();
      var first = new GeometryPlanInput(p, 1, false, Vec3.ZERO, new Vec3(0,0,1), null, null);
      var fallback = cache.build(first);
      assertTrue(fallback.ghostBlocks().isEmpty());
      assertSame(fallback, cache.build(first));
      assertEquals(1, failures.size());
      assertEquals(Set.of(BlockPos.ZERO), cache.build(new GeometryPlanInput(p, 2, false, first.eye(), first.view(), null, null)).ghostBlocks());
      assertEquals(2, calls.get());
   }

   @Test void independentOwnersKeepTheirOwnPlans() {
      var a = GeometryPreviewPlan.controlPoints(List.of(BlockPos.ZERO), null);
      var b = GeometryPreviewPlan.controlPoints(List.of(new BlockPos(5,0,0)), null);
      var first = new GeometryPlanCache((message, failure) -> fail(), input -> a);
      var second = new GeometryPlanCache((message, failure) -> fail(), input -> b);
      var input = new GeometryPlanInput(GeometryPreviewPayload.inactive(), 1, false, Vec3.ZERO, new Vec3(0,0,1), null, null);
      assertSame(a, first.build(input));
      assertSame(b, second.build(input));
      first.clear();
      assertSame(b, second.build(input));
   }
}
