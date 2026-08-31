package io.github.fastformer.client.placement;

import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.OperationWorkspacePlanCodec;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.network.payload.placement.ConfirmPayload;
import io.github.fastformer.network.payload.operation.OperationApplyPayload;
import io.github.fastformer.network.payload.operation.OperationWorkspaceApplyPayload;
import io.github.fastformer.network.payload.placement.QuickReplacePayload;
import io.github.fastformer.network.payload.placement.QuickShapePayload;
import io.github.fastformer.network.payload.placement.ShapePlacementPayload;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Single client boundary for requests that may ultimately modify the world. */
public final class ClientPlacementRouter {
   private ClientPlacementRouter() {
   }

   public static boolean canConfirm(Minecraft minecraft) {
      return supports(minecraft, ShapePlacementPayload.TYPE)
         || supports(minecraft, ConfirmPayload.TYPE);
   }

   public static boolean confirm(Minecraft minecraft) {
      ShapeSubmissionResult clientResult = submitClientShape(minecraft);
      if (clientResult != ShapeSubmissionResult.UNSUPPORTED) {
         return clientResult == ShapeSubmissionResult.SENT;
      }
      return send(minecraft, ConfirmPayload.TYPE, ConfirmPayload.INSTANCE);
   }

   public static boolean quickShape(Minecraft minecraft) {
      ShapeSubmissionResult clientResult = submitClientShape(minecraft);
      if (clientResult != ShapeSubmissionResult.UNSUPPORTED) {
         return clientResult == ShapeSubmissionResult.SENT;
      }
      return send(minecraft, QuickShapePayload.TYPE, QuickShapePayload.INSTANCE);
   }

   /** Sends a client-resolved shape package when the current preview is ready. */
   private static ShapeSubmissionResult submitClientShape(Minecraft minecraft) {
      if (!supports(minecraft, ShapePlacementPayload.TYPE)) {
         return ShapeSubmissionResult.UNSUPPORTED;
      }
      try {
         Optional<OperationWorkspacePlan> clientShape = FastPlaceClientPreview.clientBuildingPlacementPlan();
         if (clientShape.isEmpty()) {
            clientShape = FastPlaceClientPreview.clientGeometryPlacementPlan();
         }
         if (clientShape.isEmpty()) {
            return ShapeSubmissionResult.NOT_READY;
         }
         Optional<ShapeSubmission> submission = prepareShapePlacement(minecraft, clientShape.get());
         if (submission.isEmpty()) {
            return ShapeSubmissionResult.NOT_READY;
         }
         submission.get().send();
         return ShapeSubmissionResult.SENT;
      } catch (IOException | RuntimeException ignored) {
         return ShapeSubmissionResult.NOT_READY;
      }
   }

   private enum ShapeSubmissionResult {
      SENT,
      NOT_READY,
      UNSUPPORTED
   }

   public static boolean applyOperation(Minecraft minecraft, boolean copy) {
      return send(minecraft, OperationApplyPayload.TYPE, new OperationApplyPayload(copy));
   }

   public static boolean quickReplace(Minecraft minecraft) {
      return send(minecraft, QuickReplacePayload.TYPE, QuickReplacePayload.INSTANCE);
   }

   public static boolean startPlacement(Minecraft minecraft, boolean embedded) {
      return send(
         minecraft,
         StartPlacementPayload.TYPE,
         embedded ? StartPlacementPayload.EMBEDDED_INSTANCE : StartPlacementPayload.INSTANCE
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

   public static Optional<ShapeSubmission> prepareShapePlacement(
      Minecraft minecraft, OperationWorkspacePlan plan
   ) throws IOException {
      if (!supports(minecraft, ShapePlacementPayload.TYPE)) {
         return Optional.empty();
      }
      byte[] compressed = OperationWorkspacePlanCodec.encodeCompressed(plan);
      UUID transferId = UUID.randomUUID();
      List<ShapePlacementPayload> chunks = chunk(
         compressed,
         ShapePlacementPayload.MAX_CHUNK_BYTES,
         (index, count, data) -> new ShapePlacementPayload(transferId, index, count, data)
      );
      return Optional.of(new ShapeSubmission(transferId, chunks));
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

   public record ShapeSubmission(UUID transferId, List<ShapePlacementPayload> chunks) {
      public ShapeSubmission {
         if (transferId == null || chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("A shape submission requires chunks");
         }
         chunks = List.copyOf(chunks);
      }

      public void send() {
         chunks.forEach(chunk -> PacketDistributor.sendToServer(chunk, new CustomPacketPayload[0]));
      }
   }
}
