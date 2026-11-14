package io.github.fastformer.client.session.preview;

import io.github.fastformer.common.mode.SessionActivity;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.preview.ActivityStatePayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewSessionPayload;
import io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Applies inbound snapshots through the same reconnect and owner gates as confirmed replays. */
public final class ClientPreviewUpdates {
   private final ClientPreviewState state;
   private final Predicate<OperationPreviewPayload> synchronizeOperation;
   private final Consumer<SessionActivity> observeActivity;

   public ClientPreviewUpdates(ClientPreviewState state, Predicate<OperationPreviewPayload> synchronizeOperation,
      Consumer<SessionActivity> observeActivity) {
      this.state = Objects.requireNonNull(state, "state");
      this.synchronizeOperation = Objects.requireNonNull(synchronizeOperation, "synchronizeOperation");
      this.observeActivity = Objects.requireNonNull(observeActivity, "observeActivity");
   }

   public void buildingSession(BuildingPreviewSessionPayload payload) {
      if (!state.holdReconnectBuildingSession(payload)) state.applyBuildingSession(payload);
   }

   public void buildingParameters(BuildingPreviewParametersPayload payload) {
      if (!state.holdReconnectBuildingParameters(payload)) state.applyBuildingParameters(payload);
   }

   public void buildingEffect(BuildingPreviewEffectPayload payload) {
      if (!state.holdReconnectBuildingEffect(payload)) state.applyBuildingEffect(payload);
   }

   public void submissionParameters(QuickShapeSubmissionParametersPayload payload) {
      state.applyQuickShapeSubmissionParameters(Objects.requireNonNull(payload, "payload"));
   }

   public void operation(OperationPreviewPayload payload) {
      Objects.requireNonNull(payload, "payload");
      if (synchronizeOperation.test(payload)) state.applyOperation(payload);
   }

   public void geometry(GeometryPreviewPayload payload) {
      if (!state.holdReconnectGeometry(payload)) state.applyGeometry(payload);
   }

   public void activity(ActivityStatePayload payload) {
      if (state.applyActivity(payload)) observeActivity.accept(payload.activity());
   }

   public boolean confirmReconnectRestore() {
      var held = state.takeHeldReconnectPreviews();
      if (held == null) return false;
      if (held.buildingSession() != null) buildingSession(held.buildingSession());
      if (held.buildingParameters() != null) buildingParameters(held.buildingParameters());
      if (held.buildingEffect() != null) buildingEffect(held.buildingEffect());
      if (held.geometry() != null) geometry(held.geometry());
      return true;
   }
}
