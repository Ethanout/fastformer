package io.github.fastformer.network.payload.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import io.github.fastformer.fastplace.geometry.generation.FaceRasterizationMode;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits;
import org.junit.jupiter.api.Test;

class SettingsPayloadTest {
   @Test
   void missingPlacementUpdateModeKeepsTheSafeDefault() {
      OpenSettingsPayload payload = new OpenSettingsPayload(
         true, FaceRasterizationMode.POINT_SWEEP, OperationConflictMode.REPLACE,
         null, List.of(), true, false, 200, 100
      );
      assertEquals(PlacementUpdateMode.CLIENT_ONLY, payload.placementUpdateMode());
      assertEquals(false, payload.vanillaBlockHistory());
   }

   @Test
   void settingsPayloadsRoundTripTheirToggleValue() {
      FriendlyByteBuf openBuffer = new FriendlyByteBuf(Unpooled.buffer());
      ResourceLocation woodFrame = ResourceLocation.fromNamespaceAndPath("fastformer", "wood_frame");
      OpenSettingsPayload.STREAM_CODEC.encode(
         openBuffer,
         new OpenSettingsPayload(
            false, FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL,
            OperationConflictMode.KEEP_EXISTING, PlacementUpdateMode.CLIENT_ONLY,
            List.of(woodFrame), true, false, 300, 120, new TinkerHistoryMergeLimits(8, 16, 40, 3, 200, 5), false, true
         )
      );
      OpenSettingsPayload open = OpenSettingsPayload.STREAM_CODEC.decode(openBuffer);
      assertEquals(false, open.middleConfirmEnabled());
      assertEquals(FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL, open.faceRasterizationMode());
      assertEquals(OperationConflictMode.KEEP_EXISTING, open.placementConflictMode());
      assertEquals(PlacementUpdateMode.CLIENT_ONLY, open.placementUpdateMode());
      assertEquals(List.of(woodFrame), open.enabledPlacementEffects());
      assertEquals(true, open.emptyHandWrench());
      assertEquals(false, open.fallingDisabled());
      assertEquals(false, open.skipRepeatedCropStages());
      assertEquals(true, open.vanillaBlockHistory());
      assertEquals(300, open.worldUndoHistoryLimit());
      assertEquals(120, open.sessionUndoHistoryLimit());
      assertEquals(new TinkerHistoryMergeLimits(8, 16, 40, 3, 200, 5), open.tinkerHistoryMergeLimits());

      FriendlyByteBuf tinkerBuffer = new FriendlyByteBuf(Unpooled.buffer());
      var tinker = new TinkerHistorySettingPayload(TinkerHistoryMergeLimits.Setting.RECENT_AFTER, 9);
      TinkerHistorySettingPayload.STREAM_CODEC.encode(tinkerBuffer, tinker);
      assertEquals(tinker, TinkerHistorySettingPayload.STREAM_CODEC.decode(tinkerBuffer));
      for (var setting : TinkerHistoryMergeLimits.Setting.values()) {
         var settingBuffer = new FriendlyByteBuf(Unpooled.buffer());
         try {
            var value = new TinkerHistorySettingPayload(setting, 42);
            TinkerHistorySettingPayload.STREAM_CODEC.encode(settingBuffer, value);
            assertEquals(value, TinkerHistorySettingPayload.STREAM_CODEC.decode(settingBuffer));
         } finally {
            settingBuffer.release();
         }
      }

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
