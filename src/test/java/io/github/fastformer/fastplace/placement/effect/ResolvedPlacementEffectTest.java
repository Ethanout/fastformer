package io.github.fastformer.fastplace.placement.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ResolvedPlacementEffectTest {
   @Test
   void failedGenerationNeverInvokesTargetTransformation() {
      java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();
      ResolvedPlacementEffect effect = new ResolvedPlacementEffect(
         ResourceLocation.fromNamespaceAndPath("fastformer", "test"),
         source -> {
            invoked.set(true);
            return source;
         },
         value -> value,
         ignored -> java.util.Map.of()
      );

      BlockGenerationResult failed = BlockGenerationResult.constraintsFailed();
      BlockGenerationResult result = effect.applyToTargets(failed);

      assertEquals(failed, result);
      assertFalse(invoked.get());
   }

   @Test
   void targetTransformReceivesReadOnlyViewWithoutEagerIteration() {
      BlockPos target = new BlockPos(4, 5, 6);
      Set<BlockPos> lazy = new AbstractSet<>() {
         @Override
         public Iterator<BlockPos> iterator() {
            throw new AssertionError("target transform must not eagerly materialize the source");
         }

         @Override
         public int size() {
            return 1;
         }
      };
      AtomicReference<Set<BlockPos>> received = new AtomicReference<>();
      ResolvedPlacementEffect effect = new ResolvedPlacementEffect(
         ResourceLocation.fromNamespaceAndPath("fastformer", "test"),
         source -> {
            received.set(source);
            return Set.of(target);
         },
         value -> value,
         ignored -> java.util.Map.of()
      );

      BlockGenerationResult result = effect.applyToTargets(new BlockGenerationResult(
         BlockGenerationResult.Status.SUCCESS, lazy
      ));

      assertEquals(Set.of(target), result.blocks());
      assertNotSame(lazy, received.get());
      assertThrows(UnsupportedOperationException.class, () -> received.get().add(target));
   }
}
