package io.github.fastformer.client;

import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.OperationWorkspacePlanCodec;
import io.github.fastformer.network.ConfirmPayload;
import io.github.fastformer.network.OperationApplyPayload;
import io.github.fastformer.network.OperationWorkspaceApplyPayload;
import io.github.fastformer.network.QuickReplacePayload;
import io.github.fastformer.network.QuickShapePayload;
import io.github.fastformer.network.StartPlacementPayload;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
      return supports(minecraft, ConfirmPayload.TYPE);
   }

   public static boolean confirm(Minecraft minecraft) {
      return send(minecraft, ConfirmPayload.TYPE, ConfirmPayload.INSTANCE);
   }

   public static boolean quickShape(Minecraft minecraft) {
      return send(minecraft, QuickShapePayload.TYPE, QuickShapePayload.INSTANCE);
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
      int chunkSize = OperationWorkspaceApplyPayload.MAX_CHUNK_BYTES;
      int chunkCount = (compressed.length + chunkSize - 1) / chunkSize;
      UUID transferId = UUID.randomUUID();
      List<OperationWorkspaceApplyPayload> chunks = new ArrayList<>(chunkCount);
      for (int index = 0; index < chunkCount; index++) {
         int from = index * chunkSize;
         int to = Math.min(compressed.length, from + chunkSize);
         chunks.add(new OperationWorkspaceApplyPayload(
            transferId, index, chunkCount, Arrays.copyOfRange(compressed, from, to)
         ));
      }
      return Optional.of(new WorkspaceSubmission(transferId, chunks));
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
