package io.github.fastformer.protocol.s2c.preview;

import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.common.quickshape.mode.FaceMode;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.quickshape.mode.LineMode;
import io.github.fastformer.common.quickshape.mode.PointMode;
import io.github.fastformer.common.quickshape.mode.PolygonVolumeShape;
import io.github.fastformer.common.placement.PlacementContextSnapshot;
import io.github.fastformer.common.quickshape.mode.RaycastPlacement;
import io.github.fastformer.common.quickshape.mode.VolumeMode;
import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BuildingPreviewCodecTest {
   @Test
   void confirmedFaceTieBiasSurvivesCodecAndModesSnapshot() {
      PlacementContextSnapshot placement = new PlacementContextSnapshot(
         new BlockPos(4, 5, 6),
         new Vec3(4.25, 5.75, 6.5),
         Direction.EAST,
         false,
         false,
         37.5F,
         Direction.WEST,
         Direction.UP,
         List.of(Direction.UP, Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.DOWN),
         true
      );
      BuildingPreviewSnapshot payload = new BuildingPreviewSnapshot(
         true,
         false,
         true,
         true,
         false,
         false,
         PolygonVolumeShape.EXTRUDE,
         List.of(BlockPos.ZERO, new BlockPos(2, 1, 0), new BlockPos(0, 0, 2)),
         0,
         BlockPos.ZERO,
         Vec3.ZERO,
         Vec3.ZERO,
         BlockPos.ZERO,
         0.0,
         PointMode.RAYCAST,
         RaycastPlacement.EMBEDDED,
         LineMode.AXIS,
         FaceMode.COORDINATE_PLANE,
         VolumeMode.PERPENDICULAR_TO_FACE,
         FillMode.SOLID,
         LineTieBias.OPPOSITE,
         placement,
         net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("fastformer", "wood_frame")
      );

      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      BuildingPreviewSessionPayload.STREAM_CODEC.encode(buffer, new BuildingPreviewSessionPayload(payload.session()));
      BuildingPreviewParametersPayload.STREAM_CODEC.encode(buffer, new BuildingPreviewParametersPayload(payload.parameters()));
      BuildingPreviewEffectPayload.STREAM_CODEC.encode(buffer, new BuildingPreviewEffectPayload(payload.effect()));
      var session = BuildingPreviewSessionPayload.STREAM_CODEC.decode(buffer).value();
      var parameters = BuildingPreviewParametersPayload.STREAM_CODEC.decode(buffer).value();
      var effect = BuildingPreviewEffectPayload.STREAM_CODEC.decode(buffer).value();
      BuildingPreviewSnapshot decoded = BuildingPreviewSnapshot.combine(session, parameters, effect);

      assertEquals(0, buffer.readableBytes());
      assertEquals(payload, decoded);
      assertEquals(LineTieBias.OPPOSITE, decoded.faceTieBias());
      assertEquals(false, decoded.middleConfirmEnabled());
      assertEquals(LineTieBias.OPPOSITE, decoded.modes().faceTieBias());
      assertEquals(placement, decoded.placementContext());
      assertEquals(payload.activePlacementEffect(), decoded.activePlacementEffect());
      assertEquals(payload.points(), decoded.session().points());
      assertEquals(payload.faceTieBias(), decoded.parameters().faceTieBias());
      assertEquals(payload.activePlacementEffect(), decoded.effect().activeEffect());
      buffer.release();
   }

   @Test
   void splitPreviewPayloadsPreserveRevisionAndValues() {
      BuildingPreviewSnapshot source = BuildingPreviewSnapshot.inactive();
      OperationCallbackScope scope = new OperationCallbackScope(
         UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()
      );
      BuildingPreviewSessionPayload session = new BuildingPreviewSessionPayload(12L, source.session(), scope);
      BuildingPreviewParametersPayload parameters = new BuildingPreviewParametersPayload(12L, source.parameters(), scope);
      BuildingPreviewEffectPayload effect = new BuildingPreviewEffectPayload(12L, source.effect(), scope);

      FriendlyByteBuf sessionBuffer = new FriendlyByteBuf(Unpooled.buffer());
      BuildingPreviewSessionPayload.STREAM_CODEC.encode(sessionBuffer, session);
      FriendlyByteBuf parameterBuffer = new FriendlyByteBuf(Unpooled.buffer());
      BuildingPreviewParametersPayload.STREAM_CODEC.encode(parameterBuffer, parameters);
      FriendlyByteBuf effectBuffer = new FriendlyByteBuf(Unpooled.buffer());
      BuildingPreviewEffectPayload.STREAM_CODEC.encode(effectBuffer, effect);

      BuildingPreviewSessionPayload decodedSession = BuildingPreviewSessionPayload.STREAM_CODEC.decode(sessionBuffer);
      BuildingPreviewParametersPayload decodedParameters = BuildingPreviewParametersPayload.STREAM_CODEC.decode(parameterBuffer);
      BuildingPreviewEffectPayload decodedEffect = BuildingPreviewEffectPayload.STREAM_CODEC.decode(effectBuffer);

      assertEquals(0, parameterBuffer.readableBytes());
      assertEquals(12L, decodedSession.revision());
      assertEquals(session.value(), decodedSession.value());
      assertEquals(scope, decodedSession.callbackScope());
      assertEquals(parameters.value(), decodedParameters.value());
      assertEquals(scope, decodedParameters.callbackScope());
      assertEquals(effect.value(), decodedEffect.value());
      assertEquals(scope, decodedEffect.callbackScope());
   }
}
