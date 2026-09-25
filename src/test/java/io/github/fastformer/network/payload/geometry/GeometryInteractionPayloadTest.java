package io.github.fastformer.network.payload.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class GeometryInteractionPayloadTest {
   private static final OperationCallbackScope SCOPE = new OperationCallbackScope(UUID.randomUUID(),
      ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
   private static final Vec3 EYE = new Vec3(3, 4, 5);
   private static final Vec3 VIEW = new Vec3(0, 0, 1);
   @Test
   void codecRoundTripsTargetActionAndGesture() {
      GeometryInteractionPayload payload = new GeometryInteractionPayload(1L,
         7, SCOPE,
         GeometryInteractionTarget.TargetType.CONTROL_POINT,
         3,
         GeometryInteractionAction.SELECT_CONTROL_POINT,
         PointerGesture.LEFT_CLICK, EYE, VIEW
      );

      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      GeometryInteractionPayload.STREAM_CODEC.encode(buffer, payload);
      GeometryInteractionPayload decoded = GeometryInteractionPayload.STREAM_CODEC.decode(buffer);

      assertEquals(payload, decoded);

      assertEquals(
         GeometryInteractionAction.CLEAR_SELECTION,
         GeometryInteractionPayload.clearSelection(1L, 7, SCOPE, PointerGesture.RIGHT_CLICK, EYE, VIEW).action()
      );
   }

   @Test
   void constructorNormalizesUntrustedInteractionFields() {
      GeometryInteractionPayload normalized = new GeometryInteractionPayload(1L, 7, SCOPE, null, 9999, null, null, EYE, VIEW);

      assertEquals(GeometryInteractionTarget.TargetType.CONTROL_POINT, normalized.targetType());
      assertEquals(1024, normalized.index());
      assertEquals(GeometryInteractionAction.CLEAR_SELECTION, normalized.action());
      assertEquals(PointerGesture.LEFT_CLICK, normalized.gesture());
   }

   @Test
   void rejectsRequestsWithoutPreviewIdentity() {
      assertThrows(IllegalArgumentException.class, () -> GeometryInteractionPayload.clearSelection(1L, 0, SCOPE, PointerGesture.LEFT_CLICK, EYE, VIEW));
      assertThrows(IllegalArgumentException.class, () -> GeometryInteractionPayload.clearSelection(1L, 7,
         OperationCallbackScope.unscoped(), PointerGesture.LEFT_CLICK, EYE, VIEW));
      assertThrows(IllegalArgumentException.class, () -> GeometryInteractionPayload.clearSelection(0, 7, SCOPE,
         PointerGesture.LEFT_CLICK, EYE, VIEW));
   }

   @Test
   void rejectsInvalidCapturedRays() {
      assertThrows(IllegalArgumentException.class, () -> GeometryInteractionPayload.clearSelection(1, 7, SCOPE,
         PointerGesture.LEFT_CLICK, EYE, Vec3.ZERO));
      assertThrows(IllegalArgumentException.class, () -> GeometryInteractionPayload.clearSelection(1, 7, SCOPE,
         PointerGesture.LEFT_CLICK, new Vec3(Double.NaN, 0, 0), VIEW));
   }
}
