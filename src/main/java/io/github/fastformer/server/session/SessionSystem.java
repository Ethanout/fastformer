package io.github.fastformer.server.session;

import io.github.fastformer.core.lifecycle.Scope;
import io.github.fastformer.core.lifecycle.ScopedKey;
import io.github.fastformer.fastplace.interaction.SpecialItemHandlers;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.session.InteractionState;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.network.FastPlaceNetwork;
import io.github.fastformer.server.feedback.TaskFeedback;
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
   final QuickShapeSystem quickShape = states.get(ScopedKey.create("quick_shape", () -> new QuickShapeSystem(this)));
   final OperationSystem operation = states.get(ScopedKey.create("operation", () -> new OperationSystem(this)));
   final GeometrySystem geometry = states.get(ScopedKey.create("geometry", () -> new GeometrySystem(this)));
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
      quickShape.session(player).ifPresent(draft -> {
         draft.setModifierHeld(held);
         FastPlaceNetwork.syncPreview(player, draft);
      });
      operation.session(player).ifPresent(session -> FastPlaceNetwork.syncOperation(player, session));
      geometry.session(player).ifPresent(session -> FastPlaceNetwork.syncGeometry(player, session));
   }

   public InteractionState classify(ServerPlayer player) {
      if (operation.active(player)) return InteractionState.OPERATION;
      if (geometry.active(player)) return InteractionState.GEOMETRY;
      if (quickShape.active(player)) return InteractionState.BUILDING;
      if (SpecialItemHandlers.isSpecial(player.getMainHandItem())) return InteractionState.SPECIAL_ITEM;
      if (player.getMainHandItem().isEmpty()) return InteractionState.OPERATION;
      if (PlaceableItems.isPlaceable(player.getMainHandItem())) return InteractionState.BUILDING;
      return InteractionState.VANILLA;
   }

   /** Ends active editors, retaining parked dimension drafts and world tasks. */
   public void cancelEditing(ServerPlayer player) {
      quickShape.endEditing(player);
      operation.cancel(player);
      geometry.cancel(player);
   }

   public void quit(ServerPlayer player) {
      SmartFillSystem.cancel(player);
      boolean restoring = HistorySystem.of(player).restoreActive(player);
      cancelEditing(player);
      if (!restoring) {
         quickShape.cancelTask(player);
         operation.cancelTask(player);
      }
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.quit"));
   }

   /** Drops player-bound editing state; queued world work keeps its own lifetime. */
   public void discardPlayer(ServerPlayer player) {
      SmartFillSystem.forget(player);
      quickShape.discardPlayer(player.getUUID());
      InteractionModifierState.reset(player);
      QuickReplaceSystem.forget(player);
      operation.remove(player);
      geometry.remove(player);
      HistorySystem.of(player).remove(player);
      ServerInputDispatcher.clearPlacementActions(player);
      FastPlaceNetwork.forgetActivity(player);
   }

   /** Parks the source editors before restoring the target dimension; tasks stay bound to their original world. */
   public void handleDimensionChange(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to) {
      if (player == null) return;
      UUID owner = player.getUUID();
      resetModifier(player);
      quickShape.parkDimensionSession(owner, from);
      operation.parkDimensionSession(owner, from);
      geometry.parkDimensionSession(owner, from);
      ServerInputDispatcher.clearPlacementActions(player);
      quickShape.restoreDimensionSession(owner, to);
      operation.restoreDimensionSession(owner, to);
      geometry.restoreDimensionSession(owner, to);
      FastPlaceNetwork.forgetActivity(player);
      syncCurrentPreview(player);
   }

   public void syncCurrentPreview(ServerPlayer player) {
      quickShape.session(player).ifPresent(draft -> draft.setModifierHeld(modifierHeld(player)));
      PreviewSync.syncInteractionUpdates(player);
      if (operation.active(player)) {
         operation.session(player).ifPresent(session -> FastPlaceNetwork.syncOperation(player, session));
      } else if (geometry.active(player)) {
         geometry.session(player).ifPresent(session -> FastPlaceNetwork.syncGeometry(player, session));
      } else {
         quickShape.session(player).ifPresentOrElse(draft -> FastPlaceNetwork.syncPreview(player, draft),
            () -> FastPlaceNetwork.syncSettings(player));
      }
   }

   private void resetModifier(ServerPlayer player) {
      InteractionModifierState.reset(player);
      quickShape.session(player).ifPresent(draft -> draft.setModifierHeld(false));
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

   public WorkspaceSubmissionLedger submissions() { return submissions; }

   @Override public void close() {
      closed = true;
      states.close();
   }

   private static void requireThread(MinecraftServer server) {
      if (server == null || !server.isSameThread()) throw new IllegalStateException("sessions require their server thread");
   }
}
