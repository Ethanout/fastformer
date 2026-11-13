package io.github.fastformer.client.network.inbound;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.session.FastPlaceActivity;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceResultPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceReceiptPayload;
import io.github.fastformer.protocol.s2c.preview.ActivityStatePayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewSessionPayload;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.protocol.s2c.settings.OpenSettingsPayload;
import io.github.fastformer.protocol.s2c.settings.FallingStatePayload;
import io.github.fastformer.network.transport.ClientboundTransport.Delivery;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ClientInboundSystemTest {
   @Test
   void inactiveConnectionCannotReachPreviewWorkspaceOrScreenHandlers() {
      var queue = ClientInboundSystem.createQueue();
      var connection = new Connection(PacketFlow.CLIENTBOUND);
      List<CustomPacketPayload> payloads = List.of(
         new BuildingPreviewSessionPayload(BuildingPreviewSnapshot.inactive().session()),
         OperationPreviewPayload.inactive(), GeometryPreviewPayload.inactive(),
         new ActivityStatePayload(FastPlaceActivity.NONE),
         new OperationWorkspaceResultPayload(UUID.randomUUID(), false, List.of()),
         new OperationWorkspaceReceiptPayload(ResourceLocation.withDefaultNamespace("overworld"), List.of()),
         new OpenSettingsPayload(true, io.github.fastformer.workspace.submission.OperationConflictMode.REPLACE,
            io.github.fastformer.fastplace.placement.PlacementUpdateMode.CLIENT_ONLY, List.of(), true, false, 200, 100,
            io.github.fastformer.common.history.HistoryCompressionLimits.DEFAULTS, true, false),
         new FallingStatePayload(true, true));
      for (var payload : payloads) queue.post(new Delivery(payload, connection));
      assertEquals(payloads.size(), queue.pendingCount());
      // There is no live player or screen in this test. Guards must reject before invoking handlers.
      assertDoesNotThrow(queue::drain);
      assertEquals(0, queue.pendingCount());
   }
}
