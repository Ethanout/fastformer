package io.github.fastformer.network.payload.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class GeometryInteractionPayloadTest {
   @Test
   void codecRoundTripsTargetActionAndGesture() {
      GeometryInteractionPayload payload = new GeometryInteractionPayload(
         GeometryInteractionTarget.TargetType.CONTROL_POINT,
         3,
         GeometryInteractionAction.SELECT_CONTROL_POINT,
         PointerGesture.LEFT_CLICK
      );

      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      GeometryInteractionPayload.STREAM_CODEC.encode(buffer, payload);
      GeometryInteractionPayload decoded = GeometryInteractionPayload.STREAM_CODEC.decode(buffer);

      assertEquals(payload, decoded);

      assertEquals(
         GeometryInteractionAction.CLEAR_SELECTION,
         GeometryInteractionPayload.clearSelection(PointerGesture.RIGHT_CLICK).action()
      );
   }

   @Test
   void constructorNormalizesUntrustedInteractionFields() {
      GeometryInteractionPayload normalized = new GeometryInteractionPayload(null, 9999, null, null);

      assertEquals(GeometryInteractionTarget.TargetType.CONTROL_POINT, normalized.targetType());
      assertEquals(1024, normalized.index());
      assertEquals(GeometryInteractionAction.CLEAR_SELECTION, normalized.action());
      assertEquals(PointerGesture.LEFT_CLICK, normalized.gesture());
   }
}
