package io.github.fastformer.common.geometry.raster.face.policy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.generation.face.FaceRasterAdapters;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FaceRasterPolicySetTest {
   @Test void defaultsRetainEveryEntranceAndHashesIncludeTheirIdentity() {
      var defaults = FaceRasterPolicySet.DEFAULT;
      assertSame(FaceRasterPolicies.CURRENT_QUAD, defaults.quad());
      assertSame(FaceRasterPolicies.CURRENT_POLYGON, defaults.polygon());
      assertSame(FaceRasterPolicies.CURRENT_VOLUME_FACE, defaults.volume());
      assertEquals(defaults.hash(), new FaceRasterPolicySet(defaults.quad(), defaults.polygon(), defaults.volume()).hash());
      assertNotEquals(defaults.hash(), new FaceRasterPolicySet(defaults.polygon(), defaults.quad(), defaults.volume()).hash());
      assertNotEquals(defaults.hash(), new FaceRasterPolicySet(defaults.quad(), defaults.polygon(), defaults.quad()).hash());
      assertTrue(defaults.hash().matches("[0-9a-f]{64}"));
   }

   @Test void invalidDataRulesKeepTheExplicitFallbackThroughSynchronization() {
      var codec = FaceRasterRule.codec(FaceRasterAdapters.currentAlgorithms());
      var fallback = codec.parse(JsonOps.INSTANCE, new JsonPrimitive("invalid")).getOrThrow();
      assertTrue(fallback.policy().isEmpty());
      assertEquals(JsonNull.INSTANCE, codec.encodeStart(JsonOps.INSTANCE, fallback).getOrThrow());
      assertEquals(fallback, codec.parse(JsonOps.INSTANCE, JsonNull.INSTANCE).getOrThrow());
      var rule = new FaceRasterRule(Optional.of(FaceRasterPolicies.CLASSIFIED));
      assertEquals(rule, codec.parse(JsonOps.INSTANCE, codec.encodeStart(JsonOps.INSTANCE, rule).getOrThrow()).getOrThrow());
   }

   @Test void stagingRetainsPoliciesAndCancellationWithoutPublishingIntermediateBlocks() {
      var checks = new AtomicInteger();
      var blocks = new AtomicInteger();
      var original = FacePolicyObserver.capture(FaceRasterPolicySet.DEFAULT, new BlockGenerationObserver() {
         @Override public void onGenerated(BlockPos point) { blocks.incrementAndGet(); }
         @Override public void checkCancelled() {
            if (checks.incrementAndGet() > 1) throw new CancellationException();
         }
      });
      var staging = FacePolicyObserver.staging(original);
      assertSame(FaceRasterPolicySet.DEFAULT, FacePolicyObserver.policies(staging));
      staging.onGenerated(BlockPos.ZERO);
      staging.checkCancelled();
      assertEquals(0, blocks.get());
      assertThrows(CancellationException.class, staging::checkCancelled);
   }
}
