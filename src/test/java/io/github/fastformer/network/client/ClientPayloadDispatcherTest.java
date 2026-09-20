package io.github.fastformer.network.client;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationWorkspaceReceiptPayload;
import io.github.fastformer.network.payload.operation.OperationWorkspaceResultPayload;
import io.github.fastformer.network.payload.preview.ActivityStatePayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientPayloadDispatcherTest {
   private final Connection connection = new Connection(PacketFlow.CLIENTBOUND);

   @BeforeEach
   @AfterEach
   void clearEvents() {
      ClientPayloadDispatcher.endWorldSession();
   }

   @Test
   void snapshotsAndResultsRemainQueuedUntilTheTickBoundary() {
      ClientPayloadDispatcher.applyBuildingSession(
         new BuildingPreviewSessionPayload(BuildingPreviewPayload.inactive().session()), connection
      );
      ClientPayloadDispatcher.applyOperationPreview(OperationPreviewPayload.inactive(), connection);
      ClientPayloadDispatcher.applyGeometryPreview(GeometryPreviewPayload.inactive(), connection);
      ClientPayloadDispatcher.applyActivity(new ActivityStatePayload(FastPlaceActivity.NONE), connection);
      ClientPayloadDispatcher.applyWorkspaceResult(
         new OperationWorkspaceResultPayload(UUID.randomUUID(), false, List.of()), connection
      );
      ClientPayloadDispatcher.applyWorkspaceReceipt(
         new OperationWorkspaceReceiptPayload(ResourceLocation.withDefaultNamespace("overworld"), List.of()), connection
      );
      // No live Minecraft player exists here. Eager scope checks would discard these.
      assertEquals(6, ClientPayloadDispatcher.pendingEvents());
   }

   @Test
   void worldExitDropsQueuedResultsBeforeAnyHandlerRuns() {
      ClientPayloadDispatcher.applyOperationPreview(OperationPreviewPayload.inactive(), connection);
      assertEquals(1, ClientPayloadDispatcher.pendingEvents());
      ClientPayloadDispatcher.endWorldSession();
      assertEquals(0, ClientPayloadDispatcher.pendingEvents());
      assertDoesNotThrow(ClientPayloadDispatcher::onClientTick);
   }

   @Test
   void missingConnectionCannotCreateAnUnscopedEvent() {
      assertThrows(NullPointerException.class, () ->
         ClientPayloadDispatcher.applyOperationPreview(OperationPreviewPayload.inactive(), null));
      assertEquals(0, ClientPayloadDispatcher.pendingEvents());
   }
}
