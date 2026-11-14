package io.github.fastformer.client.interaction.pointer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.ClientInputSession;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PointerResolverTest {
   private final Object player = new Object();
   private final Object world = new Object();
   private final PointerResolver.View view = new PointerResolver.View(player, world, Vec3.ZERO, new Vec3(0, 0, 1), 1);

   @Test void consumersShareOneSamplePerPolicyAndKeepItsMatchingDistance() {
      var resolver = new PointerResolver();
      var outline = result(12);
      var placement = result(40);
      var selection = result(7);
      assertSame(outline, resolver.resolve(view, PointerResolver.Policy.OUTLINE, () -> outline));
      assertSame(placement, resolver.resolve(view, PointerResolver.Policy.PLACEMENT, () -> placement));
      assertSame(selection, resolver.resolve(view, PointerResolver.Policy.SELECTION, () -> selection));
      for (var policy : PointerResolver.Policy.values()) {
         var cached = resolver.resolve(view, policy, () -> fail("duplicate raycast"));
         assertSame(switch (policy) { case OUTLINE -> outline; case PLACEMENT -> placement; case SELECTION -> selection; }, cached);
      }
      assertEquals(12, outline.distance());
      assertEquals(40, placement.distance());
      assertEquals(7, selection.distance());
   }

   @Test void changingPoseTickPlayerOrWorldInvalidatesAllPolicies() {
      var resolver = new PointerResolver();
      var count = new AtomicInteger();
      var views = java.util.List.of(view,
         new PointerResolver.View(player, world, new Vec3(1, 0, 0), view.direction(), 1),
         new PointerResolver.View(player, world, new Vec3(1, 0, 0), new Vec3(1, 0, 0), 1),
         new PointerResolver.View(player, world, Vec3.ZERO, view.direction(), 2),
         new PointerResolver.View(new Object(), world, Vec3.ZERO, view.direction(), 2),
         new PointerResolver.View(player, new Object(), Vec3.ZERO, view.direction(), 2));
      for (var current : views) {
         for (var policy : PointerResolver.Policy.values()) {
            resolver.resolve(current, policy, () -> result(count.incrementAndGet()));
            resolver.resolve(current, policy, () -> fail("duplicate raycast"));
         }
      }
      assertEquals(views.size() * PointerResolver.Policy.values().length, count.get());
   }

   @Test void equalPoseValuesShareSamplesButEnvironmentKeysUseIdentity() {
      var resolver = new PointerResolver();
      var first = new PointerResolver.View(new String("player"), new String("world"), Vec3.ZERO, view.direction(), 1);
      var sample = result(10);
      resolver.resolve(first, PointerResolver.Policy.OUTLINE, () -> sample);
      var equalPose = new PointerResolver.View(first.player(), first.world(), new Vec3(0, 0, 0), new Vec3(0, 0, 1), 1);
      assertSame(sample, resolver.resolve(equalPose, PointerResolver.Policy.OUTLINE, () -> fail("equal pose was not reused")));
      var differentOwner = new PointerResolver.View(new String("player"), first.world(), Vec3.ZERO, view.direction(), 1);
      var newer = result(20);
      assertSame(newer, resolver.resolve(differentOwner, PointerResolver.Policy.OUTLINE, () -> newer));
   }

   @Test void explicitBoundaryResamplesAnUnchangedView() {
      var resolver = new PointerResolver();
      resolver.resolve(view, PointerResolver.Policy.OUTLINE, () -> result(10));
      resolver.invalidate();
      var changedWorld = result(20);
      assertSame(changedWorld, resolver.resolve(view, PointerResolver.Policy.OUTLINE, () -> changedWorld));
   }

   @Test void failedRaycastDoesNotPoisonTheFollowingSample() {
      var resolver = new PointerResolver();
      assertThrows(IllegalStateException.class, () -> resolver.resolve(view, PointerResolver.Policy.OUTLINE, () -> {
         throw new IllegalStateException("unavailable");
      }));
      var available = result(8);
      assertSame(available, resolver.resolve(view, PointerResolver.Policy.OUTLINE, () -> available));
   }

   @Test void inputOwnersHaveIndependentSamplesAndResetReleasesTheirHits() {
      var first = new ClientInputSession();
      var second = new ClientInputSession();
      var firstHit = result(5);
      var secondHit = result(6);
      first.pointer().resolve(view, PointerResolver.Policy.OUTLINE, () -> firstHit);
      second.pointer().resolve(view, PointerResolver.Policy.OUTLINE, () -> secondHit);
      first.reset();
      var updated = result(7);
      assertSame(updated, first.pointer().resolve(view, PointerResolver.Policy.OUTLINE, () -> updated));
      assertSame(secondHit, second.pointer().resolve(view, PointerResolver.Policy.OUTLINE, () -> fail("another owner's reset cleared this sample")));
   }

   private static LongRangeBlockRaycast.Result result(double distance) {
      var hit = new BlockHitResult(new Vec3(0, 0, distance), Direction.NORTH, new BlockPos(0, 0, (int) distance), false);
      return new LongRangeBlockRaycast.Result(hit, distance, distance, LongRangeBlockRaycast.Limit.UNLOADED_CHUNK, 1, 0);
   }
}
