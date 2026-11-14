package io.github.fastformer.client.operation.pointer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.InteractionIntentProvider;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.interaction.pointer.PointerResolver;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SelectionPointerFrameTest {
   private final ClientSelectionSession selection = new ClientSelectionSession();

   @Test void visualConsumersShareOneResultWhilePhysicalInputResolvesAgain() {
      var samples = new AtomicInteger();
      var resolver = resolver(context -> Optional.of(new OperationInteractionIntent.CreateSelection(
         new BlockPos(samples.incrementAndGet(), 0, 0))));
      var firstFrame = resolver.captureFrame(context());
      assertEquals(1, samples.get());
      for (int i = 0; i < 10; i++) {
         assertSame(firstFrame, resolver.frame());
         assertNull(resolver.frame().gizmo());
      }
      assertEquals(1, samples.get(), "drawing must not resolve again");
      var click = resolver.resolve(context()).orElseThrow();
      assertEquals(new BlockPos(2, 0, 0), ((OperationInteractionIntent.CreateSelection) click).point());
      assertSame(firstFrame, resolver.frame(), "physical input must not replace the visual snapshot");
      assertNotSame(firstFrame, resolver.captureFrame(context()));
      assertEquals(3, samples.get());
   }

   @Test void emptyFramesAreCapturedAndSessionBoundariesCannotExposeAnOldTarget() {
      var samples = new AtomicInteger();
      var resolver = resolver(context -> {
         samples.incrementAndGet();
         return Optional.of(new OperationInteractionIntent.CreateSelection(BlockPos.ZERO));
      });
      resolver.captureFrame(context());
      var oldOwner = resolver.frame().owner();
      selection.clearTransientInteraction();
      assertNotEquals(oldOwner, resolver.frame().owner());
      assertTrue(resolver.frame().target().isEmpty());
      assertEquals(1, samples.get());
      assertTrue(resolver.captureFrame(null).target().isEmpty());
      assertEquals(1, samples.get());
      resolver.captureFrame(context());
      resolver.clear();
      assertTrue(resolver.frame().target().isEmpty());
   }

   @Test void aFailedFrameCannotLeaveTheLastSuccessfulTargetVisible() {
      var fail = new AtomicReference<Boolean>(false);
      var resolver = resolver(context -> {
         if (fail.get()) throw new IllegalStateException("bad pointer provider");
         return Optional.of(new OperationInteractionIntent.CreateSelection(BlockPos.ZERO));
      });
      resolver.captureFrame(context());
      fail.set(true);
      assertThrows(IllegalStateException.class, () -> resolver.captureFrame(context()));
      assertTrue(resolver.frame().target().isEmpty());
      fail.set(false);
      assertTrue(resolver.captureFrame(context()).target().isPresent());
   }

   @Test void clearingOneSelectionDoesNotClearAnotherSelectionsFrame() {
      var first = resolver(context -> Optional.of(new OperationInteractionIntent.CreateSelection(BlockPos.ZERO)));
      var other = new ClientSelectionSession();
      var second = new WorkspacePointerResolver(other, () -> false, Set::of, new PointerResolver(),
         context -> Optional.of(new OperationInteractionIntent.CreateSelection(new BlockPos(1, 0, 0))));
      first.captureFrame(context());
      var secondFrame = second.captureFrame(context());
      first.clear();
      assertSame(secondFrame, second.frame());
      assertTrue(second.frame().target().isPresent());
   }

   private WorkspacePointerResolver resolver(InteractionIntentProvider creation) {
      return new WorkspacePointerResolver(selection, () -> false, Set::of, new PointerResolver(), creation);
   }

   private static InteractionContext context() {
      return new InteractionContext(null, null, Vec3.ZERO, new Vec3(1, 0, 0), Vec3.ZERO, false, false, false);
   }
}
