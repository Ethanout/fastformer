package io.github.fastformer.client.input;

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

   @Test
   void resolvedNonGizmoTargetsAlwaysProvideHoverText() {
      assertEquals(true, new OperationInteractionIntent.Part(1, 1.0).hoverText().isPresent());
      assertEquals(true, new OperationInteractionIntent.Face(
         1, new net.minecraft.world.phys.AABB(BlockPos.ZERO),
         new io.github.fastformer.fastplace.geometry.OperationGeometry.RayHit(
            Vec3.ZERO, Vec3.ZERO, 0.0, 0
         ), true
      ).hoverText().isPresent());
      assertEquals(true, new OperationInteractionIntent.CreateSelection(BlockPos.ZERO).hoverText().isPresent());
   }

   @Test
   void gizmoTargetsProvideOperationHoverText() {
      io.github.fastformer.fastplace.geometry.AxisGizmo gizmo =
         new io.github.fastformer.fastplace.geometry.AxisGizmo(Vec3.ZERO, 1.0, 0.1);
      io.github.fastformer.fastplace.geometry.AxisGizmo.Handle handle = gizmo.handles().getFirst();
      OperationInteractionIntent.Gizmo target = new OperationInteractionIntent.Gizmo(
         1, false, gizmo, new io.github.fastformer.fastplace.geometry.AxisGizmo.Hit(handle, Vec3.ZERO, 1.0, 0.1)
      );
      assertEquals(true, target.hoverText().isPresent());
   }

   @Test
   void resolvedTargetsExposeAvailabilityWithoutChangingDefaultBehavior() {
      OperationInteractionIntent target = new OperationInteractionIntent.Part(1, 1.0);
      assertEquals(OperationInteractionIntent.Availability.AVAILABLE, target.availability());
      assertEquals(true, target.unavailableReason().isEmpty());
   }

   @Test
   void builtInTargetsRequireConfiguredHoverText() {
      assertInstanceOf(net.minecraft.network.chat.Component.class,
         new OperationInteractionIntent.Part(1, 1.0).requireHoverText());
      assertInstanceOf(net.minecraft.network.chat.Component.class,
         new OperationInteractionIntent.CreateSelection(BlockPos.ZERO).requireHoverText());
   }

   @Test
   void adjustableFaceSuppliesBothCrosshairCommands() {
      OperationInteractionIntent.Face face = new OperationInteractionIntent.Face(
         1,
         new net.minecraft.world.phys.AABB(BlockPos.ZERO),
         new io.github.fastformer.fastplace.geometry.OperationGeometry.RayHit(
            Vec3.ZERO, new Vec3(1.0, 0.0, 0.0), 0.5, 0
         ),
         true
      );

      assertEquals(2, face.requireHoverTextLines().size());
   }
}
