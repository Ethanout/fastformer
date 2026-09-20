package io.github.fastformer.network.client;

import io.github.fastformer.fastplace.FaceRasterizationMode;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.client.session.ClientSessionManager;
import io.github.fastformer.client.session.ClientTickMailbox;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationWorkspaceResultPayload;
import io.github.fastformer.network.payload.preview.ActivityStatePayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload;
import io.github.fastformer.network.payload.preview.QuickShapeSubmissionParametersPayload;
import io.github.fastformer.network.payload.settings.OpenSettingsPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import io.github.fastformer.network.payload.operation.OperationWorkspaceReceiptPayload;
import io.github.fastformer.network.payload.placement.PlacementActionAckPayload;

/** Isolates physical-client entry points from common network registration code. */
public final class ClientPayloadDispatcher {
   private static final String PREVIEW_CLASS = "io.github.fastformer.client.render.FastPlaceClientPreview";
   private static final String OPERATION_CLASS =
      "io.github.fastformer.client.operation.controller.ClientOperationController";
   private static final String SETTINGS_CLASS = "io.github.fastformer.client.ui.FastFormerSettingsScreen";
   private static final ClientTickMailbox<PendingPayload> EVENTS = new ClientTickMailbox<>(event -> { });

   private ClientPayloadDispatcher() {
   }

   public static void applyBuildingSession(BuildingPreviewSessionPayload payload, Connection sourceConnection) {
      EVENTS.post(new PendingPayload(payload, sourceConnection));
   }

   public static void applyQuickShapeSubmissionParameters(QuickShapeSubmissionParametersPayload payload, Connection sourceConnection) {
      EVENTS.post(new PendingPayload(payload, sourceConnection));
   }

   private static void deliverQuickShapeSubmissionParameters(QuickShapeSubmissionParametersPayload payload, Connection connection) {
      if (ClientSessionManager.instance().acceptsPreviewCallback(payload.callbackScope(), connection)) {
         invokeStatic(PREVIEW_CLASS, "applyQuickShapeSubmissionParameters", QuickShapeSubmissionParametersPayload.class, payload);
      }
   }

   private static void deliverBuildingSession(BuildingPreviewSessionPayload payload, Connection sourceConnection) {
      if (!ClientSessionManager.instance().acceptsPreviewCallback(payload.callbackScope(), sourceConnection)) {
         return;
      }
      invokeStatic(PREVIEW_CLASS, "applyBuildingSession", BuildingPreviewSessionPayload.class, payload);
   }

   public static void applyBuildingParameters(BuildingPreviewParametersPayload payload, Connection sourceConnection) {
      EVENTS.post(new PendingPayload(payload, sourceConnection));
   }

   private static void deliverBuildingParameters(BuildingPreviewParametersPayload payload, Connection sourceConnection) {
      if (!ClientSessionManager.instance().acceptsPreviewCallback(payload.callbackScope(), sourceConnection)) {
         return;
      }
      invokeStatic(PREVIEW_CLASS, "applyBuildingParameters", BuildingPreviewParametersPayload.class, payload);
   }

   public static void applyBuildingEffect(BuildingPreviewEffectPayload payload, Connection sourceConnection) {
      EVENTS.post(new PendingPayload(payload, sourceConnection));
   }

   private static void deliverBuildingEffect(BuildingPreviewEffectPayload payload, Connection sourceConnection) {
      if (!ClientSessionManager.instance().acceptsPreviewCallback(payload.callbackScope(), sourceConnection)) {
         return;
      }
      invokeStatic(PREVIEW_CLASS, "applyBuildingEffect", BuildingPreviewEffectPayload.class, payload);
   }

   public static void applyOperationPreview(OperationPreviewPayload payload, Connection sourceConnection) {
      EVENTS.post(new PendingPayload(payload, sourceConnection));
   }

   private static void deliverOperationPreview(OperationPreviewPayload payload, Connection sourceConnection) {
      if (!ClientSessionManager.instance().acceptsOperationPreviewCallback(payload.callbackScope(), sourceConnection)) {
         return;
      }
      invokeStatic(PREVIEW_CLASS, "applyOperation", OperationPreviewPayload.class, payload);
   }

   public static void applyGeometryPreview(GeometryPreviewPayload payload, Connection sourceConnection) {
      EVENTS.post(new PendingPayload(payload, sourceConnection));
   }

   private static void deliverGeometryPreview(GeometryPreviewPayload payload, Connection sourceConnection) {
      if (!ClientSessionManager.instance().acceptsPreviewCallback(payload.callbackScope(), sourceConnection)) {
         return;
      }
      invokeStatic(PREVIEW_CLASS, "applyGeometry", GeometryPreviewPayload.class, payload);
   }

   public static void applyActivity(ActivityStatePayload payload, Connection sourceConnection) {
      EVENTS.post(new PendingPayload(payload, sourceConnection));
   }

   private static void deliverActivity(ActivityStatePayload payload, Connection sourceConnection) {
      if (!ClientSessionManager.instance().acceptsPreviewCallback(payload.callbackScope(), sourceConnection)) {
         return;
      }
      invokeStatic(PREVIEW_CLASS, "applyActivity", ActivityStatePayload.class, payload);
   }

   public static void acknowledgePlacement(
      io.github.fastformer.network.payload.placement.PlacementActionAckPayload payload, Connection sourceConnection
   ) {
      EVENTS.post(new PendingPayload(payload, sourceConnection));
   }

   private static void deliverPlacementAcknowledgement(PlacementActionAckPayload payload, Connection sourceConnection) {
      if (!ClientSessionManager.instance().acceptsCallback(payload.callbackScope(), sourceConnection)) {
         return;
      }
      invokeStatic("io.github.fastformer.client.input.FastPlaceClientInput", "acknowledgePlacementRequest",
         io.github.fastformer.network.payload.placement.PlacementActionAckPayload.class, payload);
   }

   public static void applyWorkspaceResult(OperationWorkspaceResultPayload payload, Connection sourceConnection) {
      EVENTS.post(new PendingPayload(payload, sourceConnection));
   }

   private static void deliverWorkspaceResult(OperationWorkspaceResultPayload payload, Connection sourceConnection) {
      if (!ClientSessionManager.instance().acceptsOperationResultCallback(payload.callbackScope(), sourceConnection)) {
         return;
      }
      invokeStatic(OPERATION_CLASS, "applyWorkspaceResult", OperationWorkspaceResultPayload.class, payload);
   }

   /**
    * Applies the answer to a receipt query.
    *
    * <p>The answer carries no callback scope. It answers the query that this client sent
    * on the current connection, so the connection check is the whole guard.</p>
    */
   public static void applyWorkspaceReceipt(
      io.github.fastformer.network.payload.operation.OperationWorkspaceReceiptPayload payload,
      Connection sourceConnection
   ) {
      EVENTS.post(new PendingPayload(payload, sourceConnection));
   }

   private static void deliverWorkspaceReceipt(OperationWorkspaceReceiptPayload payload, Connection sourceConnection) {
      if (!ClientSessionManager.instance().acceptsReceiptCallback(sourceConnection)) {
         return;
      }
      invokeStatic(
         OPERATION_CLASS, "applyWorkspaceReceipt",
         io.github.fastformer.network.payload.operation.OperationWorkspaceReceiptPayload.class, payload
      );
   }

   public static void onClientTick() {
      EVENTS.drain(ClientPayloadDispatcher::deliver);
   }

   public static void endWorldSession() {
      EVENTS.invalidate();
   }

   static int pendingEvents() {
      return EVENTS.pendingCount();
   }

   private static void deliver(PendingPayload event) {
      Connection connection = event.connection();
      switch (event.payload()) {
         case BuildingPreviewSessionPayload payload -> deliverBuildingSession(payload, connection);
         case QuickShapeSubmissionParametersPayload payload -> deliverQuickShapeSubmissionParameters(payload, connection);
         case BuildingPreviewParametersPayload payload -> deliverBuildingParameters(payload, connection);
         case BuildingPreviewEffectPayload payload -> deliverBuildingEffect(payload, connection);
         case OperationPreviewPayload payload -> deliverOperationPreview(payload, connection);
         case GeometryPreviewPayload payload -> deliverGeometryPreview(payload, connection);
         case ActivityStatePayload payload -> deliverActivity(payload, connection);
         case PlacementActionAckPayload payload -> deliverPlacementAcknowledgement(payload, connection);
         case OperationWorkspaceResultPayload payload -> deliverWorkspaceResult(payload, connection);
         case OperationWorkspaceReceiptPayload payload -> deliverWorkspaceReceipt(payload, connection);
         default -> throw new IllegalArgumentException("Unsupported client event: " + event.payload().type());
      }
   }

   private record PendingPayload(CustomPacketPayload payload, Connection connection) {
      private PendingPayload {
         java.util.Objects.requireNonNull(payload, "payload");
         java.util.Objects.requireNonNull(connection, "connection");
      }
   }

   public static void openSettings(OpenSettingsPayload payload) {
      try {
         Class<?> handler = Class.forName(SETTINGS_CLASS);
         handler.getMethod(
            "open",
            boolean.class,
            FaceRasterizationMode.class,
            OperationConflictMode.class,
            PlacementUpdateMode.class,
            java.util.List.class,
            boolean.class,
            boolean.class,
            int.class,
            int.class
         ).invoke(
            null,
            payload.middleConfirmEnabled(),
            payload.faceRasterizationMode(),
            payload.placementConflictMode(),
            payload.placementUpdateMode(),
            payload.enabledPlacementEffects(),
            payload.emptyHandWrench(),
            payload.globalFrozen(),
            payload.worldUndoHistoryLimit(),
            payload.sessionUndoHistoryLimit()
         );
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("Unable to open FastFormer settings", exception);
      }
   }

   private static void invokeStatic(String className, String methodName, Class<?> parameterType, Object payload) {
      try {
         Class<?> handler = Class.forName(className);
         handler.getMethod(methodName, parameterType).invoke(null, payload);
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException(
            "Unable to dispatch FastFormer client payload to " + className + "." + methodName,
            exception
         );
      }
   }
}
