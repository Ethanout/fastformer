package io.github.fastformer.client.quickshape;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.fastplace.geometry.generation.GenerationFailed;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
import io.github.fastformer.fastplace.placement.plan.PlacementGeometryPlan;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

public class QuickShapeSubmissionIntentTest {
   @Test
   void waitsForComputationAndConsumesItsFrozenResultOnlyOnce() {
      var intent = new QuickShapeSubmissionIntent();
      var snapshot = snapshot(LineMode.AXIS);
      var executor = new ArrayDeque<Runnable>();
      assertTrue(intent.begin(1, snapshot));
      assertFalse(intent.begin(2, snapshot(LineMode.FREE_SCROLL)));
      assertTrue(intent.waitingForParameters());
      intent.calculate(plan(snapshot, 20, null), executor::add);
      assertTrue(intent.takeCompleted().isEmpty());
      executor.remove().run();
      var completion = intent.takeCompleted().orElseThrow();
      assertEquals(1, completion.requestId());
      assertSame(snapshot, completion.snapshot());
      assertEquals(QuickShapeSubmissionIntent.Outcome.READY, completion.outcome());
      assertTrue(intent.takeCompleted().isEmpty());
      assertFalse(intent.active());
   }

   @Test
   void cancellationBeforeWorkerStartCannotCompleteANewIntent() {
      var intent = new QuickShapeSubmissionIntent();
      var executor = new ArrayDeque<Runnable>();
      var data = snapshot(LineMode.AXIS);
      intent.begin(1, data);
      intent.calculate(plan(data, 20, null), executor::add);
      intent.cancel();
      assertTrue(intent.begin(2, data));
      executor.remove().run();
      assertTrue(intent.takeCompleted().isEmpty());
      assertEquals(2, intent.requestId());
      intent.calculate(plan(data, 20, null), Runnable::run);
      assertEquals(2, intent.takeCompleted().orElseThrow().requestId());
   }

   @Test
   void cancelDuringComputationDiscardsLateWorkEvenWhenEffectIgnoresInterruption() throws Exception {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var exited = new CountDownLatch(1);
      var effect = new ResolvedPlacementEffect(ResourceLocation.fromNamespaceAndPath("fastformer", "test_wait"),
         blocks -> {
            entered.countDown();
            while (release.getCount() > 0) {
               try { release.await(); } catch (InterruptedException ignored) { }
            }
            return blocks;
         }, count -> count, blocks -> Map.of());
      var data = snapshot(LineMode.AXIS);
      var intent = new QuickShapeSubmissionIntent();
      intent.begin(1, data);
      intent.calculate(plan(data, 20, effect), command -> {
         Thread thread = new Thread(() -> { try { command.run(); } finally { exited.countDown(); } });
         thread.setDaemon(true);
         thread.start();
      });
      try {
         assertTrue(entered.await(5, TimeUnit.SECONDS));
         intent.cancel();
         assertTrue(intent.begin(2, data));
      } finally {
         release.countDown();
      }
      assertTrue(exited.await(5, TimeUnit.SECONDS));
      assertTrue(intent.takeCompleted().isEmpty());
      assertEquals(2, intent.requestId());
      intent.cancel();
   }

   @Test
   void confirmedPointPassesButScrollCandidateExceedingLimitFails() {
      var intent = new QuickShapeSubmissionIntent();
      var ordinary = snapshot(LineMode.AXIS);
      intent.begin(1, ordinary);
      intent.calculate(plan(ordinary, 1, null), Runnable::run);
      assertEquals(QuickShapeSubmissionIntent.Outcome.READY, intent.takeCompleted().orElseThrow().outcome());
      var scroll = snapshot(LineMode.FREE_SCROLL);
      assertTrue(intent.begin(2, scroll));
      intent.calculate(plan(scroll, 1, null), Runnable::run);
      assertEquals(QuickShapeSubmissionIntent.Outcome.LIMIT_EXCEEDED, intent.takeCompleted().orElseThrow().outcome());
      assertTrue(intent.takeCompleted().isEmpty());
      assertTrue(intent.begin(3, ordinary));
   }

   @Test
   void constraintFailureDoesNotBecomeReadyAndRequiresANewIntent() {
      var data = snapshot(LineMode.AXIS);
      var intent = new QuickShapeSubmissionIntent();
      var effect = new ResolvedPlacementEffect(ResourceLocation.fromNamespaceAndPath("fastformer", "test_fail"),
         blocks -> GenerationFailed.faceConstraints(), count -> count, blocks -> Map.of());
      intent.begin(1, data);
      intent.calculate(plan(data, 20, effect), Runnable::run);
      assertEquals(QuickShapeSubmissionIntent.Outcome.CONSTRAINTS_FAILED, intent.takeCompleted().orElseThrow().outcome());
      assertTrue(intent.takeCompleted().isEmpty());
      assertTrue(intent.begin(2, data));
   }

   @Test
   void requestIdentityCannotBeReusedForAReplacementIntent() {
      var intent = new QuickShapeSubmissionIntent();
      var first = snapshot(LineMode.AXIS);
      var second = snapshot(LineMode.FREE_SCROLL);
      assertTrue(intent.begin(11, first));
      assertTrue(intent.accepts(11, first));
      intent.cancel();
      assertTrue(intent.begin(12, second));
      assertFalse(intent.accepts(11, first));
      assertFalse(intent.accepts(12, first));
      assertTrue(intent.accepts(12, second));
   }

   public static QuickShapeSubmissionSnapshot snapshot(LineMode mode) {
      var d = BuildingPreviewPayload.inactive();
      var data = new BuildingPreviewPayload(true, true, true, false, false, false, d.polygonVolumeShape(),
         List.of(new BlockPos(1, 2, 3)), 0, new BlockPos(3, 0, 0), d.faceBaseOffset(), d.volumeBaseOffset(),
         d.perpendicularAnchor(), d.angleDegrees(), d.pointMode(), d.raycastPlacement(), mode, d.faceMode(),
         d.volumeMode(), d.fillMode(), d.faceTieBias(), null);
      return new QuickShapeSubmissionSnapshot(1, new OperationCallbackScope(UUID.randomUUID(),
         ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()), data);
   }

   private static PlacementGeometryPlan plan(QuickShapeSubmissionSnapshot snapshot, int max, ResolvedPlacementEffect effect) {
      return new PlacementGeometryPlan(snapshot.points(), snapshot.data().modes(), false,
         snapshot.data().polygonVolumeShape(), max, effect);
   }
}
