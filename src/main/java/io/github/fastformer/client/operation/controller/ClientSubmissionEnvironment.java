package io.github.fastformer.client.operation.controller;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.operation.submission.SubmissionEnvironment;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;

/** Adapts the active connection and input request lifecycle at the editor assembly boundary. */
final class ClientSubmissionEnvironment implements SubmissionEnvironment {
   private final BooleanSupplier current;

   ClientSubmissionEnvironment(BooleanSupplier current) { this.current = current; }

   public boolean available() {
      var minecraft = Minecraft.getInstance();
      return current.getAsBoolean() && minecraft != null && minecraft.getConnection() != null;
   }

   public boolean supported() { return ClientPlacementRouter.canSubmitWorkspace(Minecraft.getInstance()); }

   public BooleanSupplier freezeOwnership() {
      var minecraft = Minecraft.getInstance();
      var connection = minecraft.getConnection();
      var level = minecraft.level;
      return () -> current.getAsBoolean() && minecraft.getConnection() == connection && minecraft.level == level;
   }

   public boolean begin(UUID id) { return FastPlaceClientInput.beginWorkspaceRequest(id); }
   public void abort(UUID id) { FastPlaceClientInput.abortWorkspaceRequest(id); }
   public void acknowledge(UUID id) { FastPlaceClientInput.acknowledgeWorkspaceRequest(id); }
   public void expire(UUID id) { FastPlaceClientInput.expireWorkspaceRequest(id); }
}
