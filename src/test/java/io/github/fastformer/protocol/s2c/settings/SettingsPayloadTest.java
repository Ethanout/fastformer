package io.github.fastformer.protocol.s2c.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import io.github.fastformer.common.history.HistoryCompressionLimits;
import org.junit.jupiter.api.Test;

class SettingsPayloadTest {
   @Test
   void missingPlacementUpdateModeKeepsTheSafeDefault() {
      OpenSettingsPayload payload = new OpenSettingsPayload(
         true, OperationConflictMode.REPLACE, null, List.of(), true, false, 200, 100,
         HistoryCompressionLimits.DEFAULTS, true, false
      );
      assertEquals(PlacementUpdateMode.CLIENT_ONLY, payload.placementUpdateMode());
      assertEquals(false, payload.vanillaBlockHistory());
   }

   @Test
   void settingsSnapshotRoundTripsItsCurrentValues() {
      FriendlyByteBuf openBuffer = new FriendlyByteBuf(Unpooled.buffer());
      ResourceLocation woodFrame = ResourceLocation.fromNamespaceAndPath("fastformer", "wood_frame");
      OpenSettingsPayload.STREAM_CODEC.encode(
         openBuffer,
         new OpenSettingsPayload(
            false, OperationConflictMode.KEEP_EXISTING, PlacementUpdateMode.CLIENT_ONLY,
            List.of(woodFrame), true, false, 300, 120, new HistoryCompressionLimits(8, 16, 40, 3, 200, 5), false, true
         )
      );
      OpenSettingsPayload open = OpenSettingsPayload.STREAM_CODEC.decode(openBuffer);
      assertEquals(false, open.middleConfirmEnabled());
      assertEquals(OperationConflictMode.KEEP_EXISTING, open.placementConflictMode());
      assertEquals(PlacementUpdateMode.CLIENT_ONLY, open.placementUpdateMode());
      assertEquals(List.of(woodFrame), open.enabledPlacementEffects());
      assertEquals(true, open.emptyHandWrench());
      assertEquals(false, open.fallingDisabled());
      assertEquals(false, open.skipRepeatedCropStages());
      assertEquals(true, open.vanillaBlockHistory());
      assertEquals(300, open.worldUndoHistoryLimit());
      assertEquals(120, open.sessionUndoHistoryLimit());
      assertEquals(new HistoryCompressionLimits(8, 16, 40, 3, 200, 5), open.tinkerHistoryMergeLimits());

      openBuffer.release();
   }
}
