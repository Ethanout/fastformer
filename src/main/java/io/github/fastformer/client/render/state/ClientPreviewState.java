package io.github.fastformer.client.render.state;

import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.preview.ActivityStatePayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import java.util.Objects;

/** Owns the latest server preview snapshots shown by the client renderer. */
public final class ClientPreviewState {
   private BuildingPreviewPayload building = BuildingPreviewPayload.inactive();
   private OperationPreviewPayload operation = OperationPreviewPayload.inactive();
   private GeometryPreviewPayload geometry = GeometryPreviewPayload.inactive();
   private FastPlaceActivity activity = FastPlaceActivity.NONE;
   private long revision;
   private long buildingVersion;
   private long geometryVersion;

   public BuildingPreviewPayload building() {
      return building;
   }

   public OperationPreviewPayload operation() {
      return operation;
   }

   public GeometryPreviewPayload geometry() {
      return geometry;
   }

   public FastPlaceActivity activity() {
      return activity;
   }

   public long revision() {
      return revision;
   }

   public long buildingVersion() {
      return buildingVersion;
   }

   public long geometryVersion() {
      return geometryVersion;
   }

   public void applyBuilding(BuildingPreviewPayload payload) {
      building = Objects.requireNonNull(payload, "payload");
      revision++;
      buildingVersion++;
   }

   public void applyOperation(OperationPreviewPayload payload) {
      operation = Objects.requireNonNull(payload, "payload");
      revision++;
   }

   public void applyGeometry(GeometryPreviewPayload payload) {
      geometry = Objects.requireNonNull(payload, "payload");
      revision++;
      geometryVersion++;
   }

   public void applyActivity(ActivityStatePayload payload) {
      activity = Objects.requireNonNull(payload, "payload").activity();
      revision++;
   }
}
