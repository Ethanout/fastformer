package io.github.fastformer.client;

import io.github.fastformer.client.input.InteractionContext;
import io.github.fastformer.client.input.InteractionIntentResolver;
import io.github.fastformer.client.input.OperationInteractionIntent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class InteractionIntentResolverTest {
   private static final InteractionContext CONTEXT = new InteractionContext(
      null, null, Vec3.ZERO, new Vec3(0.0, 0.0, 1.0), Vec3.ZERO, false, false, false
   );

   @Test
   void returnsTheFirstMountedComponentIntent() {
      OperationInteractionIntent.Part selection = new OperationInteractionIntent.Part(4, 2.0);
      Optional<OperationInteractionIntent> resolved = InteractionIntentResolver.resolve(
         CONTEXT,
         List.of(context -> Optional.of(selection),
            context -> Optional.of(new OperationInteractionIntent.CreateSelection(BlockPos.ZERO)))
      );

      assertEquals(selection, resolved.orElseThrow());
   }

   @Test
   void doesNotReadLowerPrioritySignalsAfterAMatch() {
      AtomicBoolean lowerPriorityRead = new AtomicBoolean();
      Optional<OperationInteractionIntent> resolved = InteractionIntentResolver.resolve(
         CONTEXT,
         List.of(
            context -> Optional.empty(),
            context -> Optional.of(new OperationInteractionIntent.Part(2, 1.0)),
            context -> {
               lowerPriorityRead.set(true);
               return Optional.of(new OperationInteractionIntent.CreateSelection(BlockPos.ZERO));
            }
         )
      );

      assertInstanceOf(OperationInteractionIntent.Part.class, resolved.orElseThrow());
      assertEquals(false, lowerPriorityRead.get());
   }
}
