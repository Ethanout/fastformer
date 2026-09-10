package io.github.fastformer.network.payload.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import io.github.fastformer.fastplace.FaceRasterizationMode;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.RaycastPlacement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsPayloadTest {
   @Test
   void settingsPayloadsRoundTripTheirToggleValue() {
      FriendlyByteBuf openBuffer = new FriendlyByteBuf(Unpooled.buffer());
      ResourceLocation woodFrame = ResourceLocation.fromNamespaceAndPath("fastformer", "wood_frame");
      OpenSettingsPayload.STREAM_CODEC.encode(
         openBuffer,
         new OpenSettingsPayload(
            false, FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL, RaycastPlacement.SURFACE,
            OperationConflictMode.KEEP_EXISTING, PlacementUpdateMode.CLIENT_ONLY,
            List.of(woodFrame), true, false, 300, 120
         )
      );
      OpenSettingsPayload open = OpenSettingsPayload.STREAM_CODEC.decode(openBuffer);
      assertEquals(false, open.middleConfirmEnabled());
      assertEquals(FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL, open.faceRasterizationMode());
      assertEquals(RaycastPlacement.SURFACE, open.raycastPlacement());
      assertEquals(OperationConflictMode.KEEP_EXISTING, open.placementConflictMode());
      assertEquals(PlacementUpdateMode.CLIENT_ONLY, open.placementUpdateMode());
      assertEquals(List.of(woodFrame), open.enabledPlacementEffects());
      assertEquals(true, open.emptyHandWrench());
      assertEquals(false, open.globalFrozen());
      assertEquals(300, open.worldUndoHistoryLimit());
      assertEquals(120, open.sessionUndoHistoryLimit());

      FriendlyByteBuf updateBuffer = new FriendlyByteBuf(Unpooled.buffer());
      MiddleConfirmSettingPayload.STREAM_CODEC.encode(updateBuffer, new MiddleConfirmSettingPayload(true));
      assertEquals(true, MiddleConfirmSettingPayload.STREAM_CODEC.decode(updateBuffer).enabled());

      FriendlyByteBuf faceBuffer = new FriendlyByteBuf(Unpooled.buffer());
      FaceRasterizationSettingPayload.STREAM_CODEC.encode(
         faceBuffer,
         new FaceRasterizationSettingPayload(FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL)
      );
      assertEquals(
         FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL,
         FaceRasterizationSettingPayload.STREAM_CODEC.decode(faceBuffer).mode()
      );

      FriendlyByteBuf actionBuffer = new FriendlyByteBuf(Unpooled.buffer());
      SettingsActionPayload.STREAM_CODEC.encode(actionBuffer, new SettingsActionPayload(SettingsActionPayload.Action.INCREASE_WORLD_HISTORY));
      assertEquals(SettingsActionPayload.Action.INCREASE_WORLD_HISTORY, SettingsActionPayload.STREAM_CODEC.decode(actionBuffer).action());

      FriendlyByteBuf effectBuffer = new FriendlyByteBuf(Unpooled.buffer());
      ResourceLocation effectId = ResourceLocation.fromNamespaceAndPath("fastformer", "wood_frame");
      PlacementEffectSettingPayload.STREAM_CODEC.encode(
         effectBuffer, new PlacementEffectSettingPayload(effectId, false)
      );
      PlacementEffectSettingPayload effect = PlacementEffectSettingPayload.STREAM_CODEC.decode(effectBuffer);
      assertEquals(effectId, effect.effectId());
      assertEquals(false, effect.enabled());
      assertThrows(
         IllegalArgumentException.class,
         () -> new PlacementEffectSettingPayload(null, true)
      );
   }
}
