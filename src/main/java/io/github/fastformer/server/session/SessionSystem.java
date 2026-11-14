package io.github.fastformer.server.session;

import io.github.fastformer.core.lifecycle.Scope;
import io.github.fastformer.core.lifecycle.ScopedKey;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.network.FastPlaceNetwork;
import io.github.fastformer.server.feedback.TaskFeedback;
import io.github.fastformer.server.mode.ServerModes;
import io.github.fastformer.server.session.execution.PlacementTasks;
import io.github.fastformer.server.session.execution.OperationTasks;
import io.github.fastformer.server.network.upload.UploadSystem;
import io.github.fastformer.server.fill.SmartFillSystem;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.server.replace.QuickReplaceSystem;
import io.github.fastformer.server.submission.WorkspaceSubmissionLedger;
import io.github.fastformer.server.sync.PreviewSync;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns durable workflows and task queues of one running server, including offline players. */
public final class SessionSystem implements AutoCloseable {
   private static final Logger LOGGER = LoggerFactory.getLogger(SessionSystem.class);
   private static final Map<MinecraftServer, SessionSystem> SERVERS = Collections.synchronizedMap(new IdentityHashMap<>());
   private final Scope states = new Scope();
   private final PlacementTasks placements = states.get(ScopedKey.create("placement_tasks", () -> new PlacementTasks(() -> this.closed)));
   private final OperationTasks operationTasks = states.get(ScopedKey.create("operation_tasks", () -> new OperationTasks(
      () -> this.closed, placements::active,
      context -> (result, player) -> UploadSystem.of(context.server()).receipts().finish(result, player))));
   final QuickShapeSystem quickShape = states.get(ScopedKey.create("quick_shape", () -> new QuickShapeSystem(this)));
   final OperationSystem operation = states.get(ScopedKey.create("operation", () -> new OperationSystem(this)));
   final GeometrySystem geometry = states.get(ScopedKey.create("geometry", () -> new GeometrySystem(this)));
   private final ServerModes modes = new ServerModes(quickShape, operation, geometry, this::quit);
   private final TaskFeedback feedback = states.get(ScopedKey.create("task_feedback", TaskFeedback::new));
   private final WorkspaceSubmissionLedger submissions = states.get(ScopedKey.create("workspace_submissions", WorkspaceSubmissionLedger::new));
   private boolean closed;

   SessionSystem() {}

   public static void start(MinecraftServer server) {
      requireThread(server);
      if (SERVERS.putIfAbsent(server, new SessionSystem()) != null) throw new IllegalStateException("session system already started");
   }

   public static SessionSystem of(MinecraftServer server) {
      return find(server).orElseThrow(() -> new IllegalStateException("session system is not running"));
   }

   public static SessionSystem of(ServerPlayer player) { return of(player.getServer()); }

   public static Optional<SessionSystem> find(MinecraftServer server) {
      requireThread(server);
      var runtime = SERVERS.get(server);
      return runtime == null || runtime.closed ? Optional.empty() : Optional.of(runtime);
   }

   /** Vanilla disconnects players after the stopping boundary; cleanup must never create a runtime. */
   public static void detachPlayer(ServerPlayer player) {
      if (player == null) return;
      find(player.getServer()).ifPresent(runtime -> {
         // A replaced entity must not clear the new entity's retained UUID workflow.
         if (PlayerStateRegistry.find(player).isPresent()) runtime.resetModifier(player);
         FastPlaceNetwork.forgetActivity(player);
      });
   }

   public boolean modifierHeld(ServerPlayer player) {
      return InteractionModifierState.held(player);
   }

   public void setModifierHeld(ServerPlayer player, boolean held) {
      InteractionModifierState.set(player, held);
      modes.modifierChanged(player, held, true);
   }

   public InteractionState classify(ServerPlayer player) {
      return modes.current(player).interactionState();
   }

   /** Ends active editors, retaining parked dimension drafts and world tasks. */
   public void cancelEditing(ServerPlayer player) {
      modes.cancelEditing(player);
   }

   public void quit(ServerPlayer player) {
      SmartFillSystem.cancel(player);
      boolean restoring = HistorySystem.of(player).restoreActive(player);
      cancelEditing(player);
      if (!restoring) {
         placements.cancel(player);
         operationTasks.cancel(player);
      }
      PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.quit"));
   }

   /** Cancels editing and world work when creative editing or recovery admission is unavailable. */
   public void stopUnavailable(ServerPlayer player) {
      SmartFillSystem.cancel(player);
      boolean taskWasActive = placements.active(player) || operationTasks.active(player);
      cancelEditing(player);
      placements.cancel(player);
      operationTasks.cancel(player);
      HistorySystem.of(player).cancel(player);
      if (taskWasActive && !io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeGate().writesAllowed()) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.recovery_journal_blocked"));
      }
   }

   /** Drops player-bound editing state; queued world work keeps its own lifetime. */
   public void discardPlayer(ServerPlayer player) {
      SmartFillSystem.forget(player);
      InteractionModifierState.reset(player);
      QuickReplaceSystem.forget(player);
      modes.discardPlayer(player);
      HistorySystem.of(player).remove(player);
      ServerInputDispatcher.clearPlacementActions(player);
      FastPlaceNetwork.forgetActivity(player);
   }

   /** Parks the source editors before restoring the target dimension; tasks stay bound to their original world. */
   public void handleDimensionChange(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to) {
      if (player == null) return;
      UUID owner = player.getUUID();
      resetModifier(player);
      modes.parkDimension(owner, from);
      ServerInputDispatcher.clearPlacementActions(player);
      modes.restoreDimension(owner, to);
      FastPlaceNetwork.forgetActivity(player);
      syncCurrentPreview(player);
   }

   public void syncCurrentPreview(ServerPlayer player) {
      modes.modifierChanged(player, modifierHeld(player), false);
      PreviewSync.syncInteractionUpdates(player);
      modes.active(player).ifPresentOrElse(mode -> mode.sync(player), () -> FastPlaceNetwork.syncSettings(player));
   }

   private void resetModifier(ServerPlayer player) {
      InteractionModifierState.reset(player);
      modes.modifierChanged(player, false, false);
   }

   /** Remove admission before cleanup, so a late lookup cannot recreate the old server runtime. */
   public static void stop(MinecraftServer server) {
      requireThread(server);
      var runtime = SERVERS.remove(server);
      if (runtime != null) {
         try { runtime.close(); }
         catch (RuntimeException failure) { LOGGER.error("FastFormer session cleanup failed", failure); }
      }
   }

   boolean closed() { return closed; }

   public TaskFeedback feedback() { return feedback; }
   public PlacementTasks placementTasks() { return placements; }
   public OperationTasks operationTasks() { return operationTasks; }

   public WorkspaceSubmissionLedger submissions() { return submissions; }
   public ServerModes modes() { return modes; }

   @Override public void close() {
      closed = true;
      states.close();
   }

   private static void requireThread(MinecraftServer server) {
      if (server == null || !server.isSameThread()) throw new IllegalStateException("sessions require their server thread");
   }
}
