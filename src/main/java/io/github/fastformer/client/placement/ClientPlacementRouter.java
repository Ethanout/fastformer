package io.github.fastformer.client.placement;

import io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot;
import io.github.fastformer.network.payload.placement.QuickShapeConfirmPayload;

import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.OperationWorkspacePlanCodec;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.network.payload.operation.OperationApplyPayload;
import io.github.fastformer.network.payload.operation.OperationWorkspaceApplyPayload;
import io.github.fastformer.network.payload.placement.QuickReplacePayload;
import io.github.fastformer.network.payload.placement.PlacementActionPayload;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Single client boundary for requests that may ultimately modify the world. */
public final class ClientPlacementRouter {
   private static final AtomicLong NEXT_ACTION_ID = new AtomicLong();
   private ClientPlacementRouter() {
   }

   public static boolean canConfirm(Minecraft minecraft) {
      return supports(minecraft, PlacementActionPayload.TYPE);
   }

   public static boolean confirm(Minecraft minecraft) {
      return sendAction(minecraft, PlacementActionPayload.Action.CONFIRM);
   }

   public static boolean quickShape(Minecraft minecraft) {
      return sendAction(minecraft, PlacementActionPayload.Action.QUICK_SHAPE);
   }

   public static boolean confirmQuickShape(
      Minecraft minecraft, QuickShapeSubmissionSnapshot snapshot
   ) {
      var type = QuickShapeConfirmPayload.TYPE;
      if (snapshot == null || !supports(minecraft, type)
         || !supports(minecraft, io.github.fastformer.network.payload.placement.PlacementActionAckPayload.TYPE)) {
         return false;
      }
      long requestId = NEXT_ACTION_ID.incrementAndGet();
      if (!io.github.fastformer.client.input.FastPlaceClientInput.beginPlacementRequest(requestId)) return false;
      return sendSubmittedAction(minecraft, type,
         new QuickShapeConfirmPayload(
            requestId, snapshot.revision(), snapshot.scope()
         ), requestId);
   }

   private static boolean sendAction(Minecraft minecraft, PlacementActionPayload.Action action) {
      if (!supports(minecraft, PlacementActionPayload.TYPE)
         || !supports(minecraft, io.github.fastformer.network.payload.placement.PlacementActionAckPayload.TYPE)) {
         return false;
      }
      long requestId = NEXT_ACTION_ID.incrementAndGet();
      if (!io.github.fastformer.client.input.FastPlaceClientInput.beginPlacementRequest(requestId)) {
         return false;
      }
      return sendSubmittedAction(
         minecraft, PlacementActionPayload.TYPE, new PlacementActionPayload(action, requestId), requestId
      );
   }

   public static boolean applyOperation(Minecraft minecraft, boolean copy) {
      if (!supports(minecraft, OperationApplyPayload.TYPE)
         || !supports(minecraft, io.github.fastformer.network.payload.placement.PlacementActionAckPayload.TYPE)) {
         return false;
      }
      long requestId = NEXT_ACTION_ID.incrementAndGet();
      if (!io.github.fastformer.client.input.FastPlaceClientInput.beginPlacementRequest(requestId)) {
         return false;
      }
      return sendSubmittedAction(
         minecraft, OperationApplyPayload.TYPE, new OperationApplyPayload(copy, requestId), requestId
      );
   }

   public static boolean quickReplace(Minecraft minecraft) {
      return send(minecraft, QuickReplacePayload.TYPE, QuickReplacePayload.INSTANCE);
   }

   public static boolean startPlacement(Minecraft minecraft, RaycastPlacement placement) {
      return send(
         minecraft,
         StartPlacementPayload.TYPE,
         StartPlacementPayload.forPlacement(placement)
      );
   }

   public static Optional<WorkspaceSubmission> prepareWorkspace(
      Minecraft minecraft, OperationWorkspacePlan plan
   ) throws IOException {
      if (!supports(minecraft, OperationWorkspaceApplyPayload.TYPE)) {
         return Optional.empty();
      }
      byte[] compressed = OperationWorkspacePlanCodec.encodeCompressed(plan);
      UUID transferId = UUID.randomUUID();
      List<OperationWorkspaceApplyPayload> chunks = chunk(
         compressed,
         OperationWorkspaceApplyPayload.MAX_CHUNK_BYTES,
         (index, count, data) -> new OperationWorkspaceApplyPayload(transferId, index, count, data)
      );
      return Optional.of(new WorkspaceSubmission(transferId, chunks));
   }

   private static <T> List<T> chunk(byte[] data, int chunkSize, ChunkFactory<T> factory) {
      int chunkCount = (data.length + chunkSize - 1) / chunkSize;
      List<T> chunks = new ArrayList<>(chunkCount);
      for (int index = 0; index < chunkCount; index++) {
         int from = index * chunkSize;
         int to = Math.min(data.length, from + chunkSize);
         chunks.add(factory.create(index, chunkCount, java.util.Arrays.copyOfRange(data, from, to)));
      }
      return List.copyOf(chunks);
   }

   private static boolean supports(Minecraft minecraft, CustomPacketPayload.Type<?> type) {
      return minecraft != null && minecraft.getConnection() != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), type.id());
   }

   private static boolean send(
      Minecraft minecraft, CustomPacketPayload.Type<?> type, CustomPacketPayload payload
   ) {
      if (!supports(minecraft, type)) return false;
      PacketDistributor.sendToServer(payload, new CustomPacketPayload[0]);
      return true;
   }

   private static boolean sendSubmittedAction(
      Minecraft minecraft, CustomPacketPayload.Type<?> type, CustomPacketPayload payload, long requestId
   ) {
      try {
         if (send(minecraft, type, payload)) {
            return true;
         }
      } catch (RuntimeException exception) {
         io.github.fastformer.client.input.FastPlaceClientInput.abortPlacementRequest(requestId);
         return false;
      }
      io.github.fastformer.client.input.FastPlaceClientInput.abortPlacementRequest(requestId);
      return false;
   }

   @FunctionalInterface
   private interface ChunkFactory<T> {
      T create(int index, int count, byte[] data);
   }

   public record WorkspaceSubmission(UUID transferId, List<OperationWorkspaceApplyPayload> chunks) {
      public WorkspaceSubmission {
         if (transferId == null || chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("A workspace submission requires chunks");
         }
         chunks = List.copyOf(chunks);
      }

      public void send() {
         this.chunks.forEach(chunk ->
            PacketDistributor.sendToServer(chunk, new CustomPacketPayload[0])
         );
      }
   }

}
