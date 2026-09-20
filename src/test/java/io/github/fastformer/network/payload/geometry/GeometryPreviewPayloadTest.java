package io.github.fastformer.network.payload.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.ConePlaneMode;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.GeometryMode;
import io.github.fastformer.fastplace.PolyhedronSizeMode;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import io.netty.buffer.Unpooled;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryPreviewPayloadTest {
   @Test
   void selectedPointIndexSurvivesCodecRoundTrip() {
      OperationCallbackScope scope = new OperationCallbackScope(
         UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()
      );
      GeometryPreviewPayload payload = new GeometryPreviewPayload(
         true,
         GeometryMode.CONVEX_POLYHEDRON,
         List.of(new BlockPos(0, 0, 0), new BlockPos(4, 0, 0)),
         List.of(new Vec3(0.5, 0.5, 0.5), new Vec3(4.5, 0.5, 0.5)),
         List.of(ControlPointRole.PRIMARY, ControlPointRole.SECONDARY),
         false,
         true,
         BlockPos.ZERO,
         1,
         2,
         3,
         PolyhedronSizeMode.RADIUS,
         FillMode.OUTLINE,
         ConePlaneMode.RADIUS,
         1.5,
         1.25,
         0.75,
         0.0,
         Vec3.ZERO,
         0.0,
         true,
         new double[]{1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0},
         new Vec3(1.0, 1.0, 1.0),
         new Vec3(1.0, 1.0, 1.0),
         false,
         1
      ).withRevision(42L).withCallbackScope(scope);

      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      GeometryPreviewPayload.STREAM_CODEC.encode(buffer, payload);
      GeometryPreviewPayload decoded = GeometryPreviewPayload.STREAM_CODEC.decode(buffer);

      assertEquals(1, decoded.selectedPointIndex());
      assertEquals(42L, decoded.revision());
      assertEquals(scope, decoded.callbackScope());
      assertEquals(payload.pointLocations(), decoded.pointLocations());
   }
}
