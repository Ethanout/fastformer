package io.github.fastformer.network.client;

import io.github.fastformer.fastplace.FaceRasterizationMode;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.RaycastPlacement;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationWorkspaceResultPayload;
import io.github.fastformer.network.payload.preview.ActivityStatePayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload;
import io.github.fastformer.network.payload.settings.OpenSettingsPayload;

/** Isolates physical-client entry points from common network registration code. */
public final class ClientPayloadDispatcher {
   private static final String PREVIEW_CLASS = "io.github.fastformer.client.render.FastPlaceClientPreview";
   private static final String OPERATION_CLASS =
      "io.github.fastformer.client.operation.controller.ClientOperationController";
   private static final String SETTINGS_CLASS = "io.github.fastformer.client.ui.FastFormerSettingsScreen";

   private ClientPayloadDispatcher() {
   }

   public static void applyBuildingPreview(BuildingPreviewPayload payload) {
      invokeStatic(PREVIEW_CLASS, "applyBuilding", BuildingPreviewPayload.class, payload);
   }

   public static void applyBuildingSession(BuildingPreviewSessionPayload payload) {
      invokeStatic(PREVIEW_CLASS, "applyBuildingSession", BuildingPreviewSessionPayload.class, payload);
   }

   public static void applyBuildingParameters(BuildingPreviewParametersPayload payload) {
      invokeStatic(PREVIEW_CLASS, "applyBuildingParameters", BuildingPreviewParametersPayload.class, payload);
   }

   public static void applyBuildingEffect(BuildingPreviewEffectPayload payload) {
      invokeStatic(PREVIEW_CLASS, "applyBuildingEffect", BuildingPreviewEffectPayload.class, payload);
   }

   public static void applyOperationPreview(OperationPreviewPayload payload) {
      invokeStatic(PREVIEW_CLASS, "applyOperation", OperationPreviewPayload.class, payload);
   }

   public static void applyGeometryPreview(GeometryPreviewPayload payload) {
      invokeStatic(PREVIEW_CLASS, "applyGeometry", GeometryPreviewPayload.class, payload);
   }

   public static void applyActivity(ActivityStatePayload payload) {
      invokeStatic(PREVIEW_CLASS, "applyActivity", ActivityStatePayload.class, payload);
   }

   public static void applyWorkspaceResult(OperationWorkspaceResultPayload payload) {
      invokeStatic(OPERATION_CLASS, "applyWorkspaceResult", OperationWorkspaceResultPayload.class, payload);
   }

   public static void openSettings(OpenSettingsPayload payload) {
      try {
         Class<?> handler = Class.forName(SETTINGS_CLASS);
         handler.getMethod(
            "open",
            boolean.class,
            FaceRasterizationMode.class,
            RaycastPlacement.class,
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
            payload.raycastPlacement(),
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
