package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.gesture.PathClosePress;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.SmartSelectionEditView;
import io.github.fastformer.client.interaction.binding.action.BindingCommands.SelectionBindingCommand;
import io.github.fastformer.client.interaction.binding.context.SelectionBindingContext;
import io.github.fastformer.client.interaction.binding.context.SelectionBindingContext.Fact;
import io.github.fastformer.client.interaction.binding.tool.SelectionBindingInput;
import io.github.fastformer.client.operation.input.SelectionPointerEvent.RemoteSnapshot;
import io.github.fastformer.client.interaction.drag.GeometryGizmoDrag;
import io.github.fastformer.client.operation.controller.OperationContext;
import io.github.fastformer.client.operation.input.OperationPointCommandPress;
import io.github.fastformer.client.operation.input.OperationPointDragPress;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointPress;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import io.github.fastformer.client.operation.input.SelectionPointerPress;
import io.github.fastformer.client.operation.pointer.OperationPointerKind;
import io.github.fastformer.client.operation.selection.tool.SelectionTool;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.protocol.c2s.geometry.ClosePathPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryInteractionPayload;
import io.github.fastformer.protocol.c2s.operation.OperationInsertPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointDragPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationSelectPointPayload;
import io.github.fastformer.protocol.c2s.placement.UndoFastPlacePayload;
import java.util.EnumSet;
import java.util.Locale;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;

import io.github.fastformer.client.network.outbound.ServerGateway;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;

import io.github.fastformer.common.raycast.InteractionRaycastRules;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.client.session.ClientSessionSystem;

import com.google.gson.*;
import io.github.fastformer.client.interaction.binding.*;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.WorkspaceCommand;
import io.github.fastformer.client.interaction.binding.action.ConfirmationCommand;
import io.github.fastformer.client.interaction.binding.action.HistoryConflictCommand;
import io.github.fastformer.client.interaction.binding.action.CancellationCommand;
import io.github.fastformer.client.interaction.binding.context.key.CancellationKeyContext;
import io.github.fastformer.client.interaction.binding.input.CancellationCapture;
import io.github.fastformer.client.interaction.binding.input.MouseButtons;
import io.github.fastformer.common.interaction.binding.DoubleClickPolicy;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.quickshape.QuickShapeSubmissionController;
import io.github.fastformer.common.interaction.binding.SessionProfiles;
import io.github.fastformer.common.raycast.*;
import io.github.fastformer.common.quickshape.QuickShapeCandidateContext;
import io.github.fastformer.protocol.c2s.placement.QuitFastPlacePayload;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;

/** Adapts data bindings to existing input commands, owning pending replay and acknowledgement cleanup. */
final class BindingInputController {
   private static final BindingCommands WORKSPACE_PROBE = new BindingCommands() {
      public boolean start(ResourceLocation profile) { return true; }
      public boolean point() { return true; }
      public boolean middleConfirm() { return true; }
      public boolean undoPoint() { return true; }
      public boolean cancel() { return true; }
      public boolean cycleMode() { return true; }
      public boolean tool(io.github.fastformer.client.interaction.binding.action.ToolCommand command) { return true; }
      public boolean workspace(io.github.fastformer.client.interaction.binding.action.WorkspaceCommand command) { return true; }
      public boolean confirmation(io.github.fastformer.client.interaction.binding.action.ConfirmationCommand command) { return true; }
      public boolean historyConflict(HistoryConflictCommand command) { return true; }
      public void replayPress(long time) { }
   };
   private BindingSystem system;
   private Object environment;
   private Object player;
   private ItemStack item;
   private ResourceLocation profile = SessionProfiles.NONE;
   private long startedAt;
   private boolean acknowledged;
   private int physicalButton = -1;
   private Release release;
   private record Release(Vec3 eye, Vec3 view, BlockHitResult hit, long occurredAt) {}

   boolean onIdlePress(Minecraft minecraft, ClientInputSession owner, int button, long now, boolean control, IdlePressSnapshot press) {
      if (press.outline() == null) return false;
      BindingSystem bindings = system(minecraft, owner);
      boolean handled = bindings.onButton(button, MouseButtons.name(button), true, now, context(minecraft, true, false),
         commands(minecraft, owner, press, control, now, null, button));
      if (handled) {
         environment = minecraft.level;
         player = minecraft.player;
         item = minecraft.player.getMainHandItem().copy();
         physicalButton = button;
         owner.idleEntry.clear();
         minecraft.options.keyUse.setDown(false);
      }
      return handled;
   }

   boolean onButton(Minecraft minecraft, ClientInputSession owner, int action, int button, long now) {
      if (action == MouseButtonInputSemantics.RELEASE) owner.idleButtons.release(button);
      boolean wasActive = active();
      if (system != null && system.synchronizeDefinitions() && wasActive) return true;
      if (system == null || !system.active() && profile.equals(SessionProfiles.NONE)) return false;
      if (!intact(minecraft)) { cancel(minecraft, owner); return false; }
      if (action == 1 && button == physicalButton) return true;
      if (action == 1 && button == 0) { cancel(minecraft, owner); return true; }
      if (button != physicalButton || action != 0) return false;
      boolean handled = system.onButton(button, MouseButtons.name(button), false, now, () -> context(minecraft, false, true),
         commands(minecraft, owner, null, false, now, null, button));
      owner.releaseQuickShapeButton(button);
      if (profile.equals(SessionProfiles.NONE) && !system.active()) clearCapturedContext();
      return handled;
   }

   CancellationCapture captureCancellation(Minecraft minecraft, ClientInputSession owner, Set<String> keys, CancellationKeyContext facts) {
      var bindings = system(minecraft, owner);
      return CancellationCapture.resolve((context, commands) -> bindings.onKeyResult(keys, context, commands), facts);
   }

   void dispatchCancellation(Minecraft minecraft, ClientInputSession owner, Set<String> keys,
      BindingContext context, KeyboardInputSnapshot input) {
      system(minecraft, owner).onKeyResult(keys, context, commands(minecraft, owner, null, false, input.occurredAtNanos(), input));
   }

   boolean onToolKey(Minecraft minecraft, ClientInputSession owner, Set<String> names, BindingContext context, long now) {
      return system(minecraft, owner).onKey(names, context, commands(minecraft, owner, null, false, now));
   }

   /** Probe uses the same rules without editing the workspace or emitting feedback. */
   boolean onWorkspaceKey(Minecraft minecraft, ClientInputSession owner, Set<String> names, BindingContext context, long now, boolean probe) {
      var result = system(minecraft, owner).onKeyResult(names, context, probe ? WORKSPACE_PROBE : commands(minecraft, owner, null, false, now));
      return probe ? result.consumesVanilla() : result.handled();
   }

   BindingAction.Result onConfirmationKey(Minecraft minecraft, ClientInputSession owner, Set<String> names, String phase,
      java.util.function.Supplier<BindingContext> contexts, KeyboardInputSnapshot input, boolean probe) {
      return system(minecraft, owner).onKeyResult(names, new BindingContext(Map.of("input_phase", new JsonPrimitive(phase))),
         contexts, probe ? WORKSPACE_PROBE
         : commands(minecraft, owner, null, false, input.occurredAtNanos(), input));
   }

   BindingAction.Result onIdleSignals(Minecraft minecraft, ClientInputSession owner, String button, boolean secondPress,
      BindingContext context, BindingCommands commands) {
      return system(minecraft, owner).onSignalsResult(button, secondPress ? Set.of("press", "double") : Set.of("press"), context, commands);
   }

   Optional<DoubleClickPolicy> idleDoublePolicy(Minecraft minecraft, ClientInputSession owner, String button, BindingContext context) {
      return system(minecraft, owner).pattern(button, "double", context).map(pattern -> pattern.doubleClick());
   }

   BindingAction.Result onHistoryKey(Minecraft minecraft, ClientInputSession owner, Set<String> names,
      BindingContext context, KeyboardInputSnapshot input, boolean probe) {
      return system(minecraft, owner).onKeyResult(names, context, probe ? WORKSPACE_PROBE
         : commands(minecraft, owner, null, false, input.occurredAtNanos(), input));
   }

   boolean onToolSignal(Minecraft minecraft, ClientInputSession owner, String name, String gesture, BindingContext context, long now) {
      return system(minecraft, owner).onSignal(name, gesture, context, commands(minecraft, owner, null, false, now));
   }

   io.github.fastformer.client.interaction.binding.action.BindingAction.Result onToolSignalResult(
      Minecraft minecraft, ClientInputSession owner, String name, String gesture, BindingContext context, long now) {
      return system(minecraft, owner).onSignalResult(name, gesture, context, commands(minecraft, owner, null, false, now));
   }

   io.github.fastformer.client.interaction.binding.action.BindingAction.Result onShapeSignal(
      Minecraft minecraft, ClientInputSession owner, String name, String gesture, BindingContext context,
      BindingCommands commands) {
      return system(minecraft, owner).onSignalResult(name, gesture, context, commands);
   }

   void synchronizeShapeDefinitions(Minecraft minecraft, ClientInputSession owner) {
      system(minecraft, owner).synchronizeDefinitions();
   }

   void tick(Minecraft minecraft, ClientInputSession owner, long now) {
      if (system == null) return;
      if (system.synchronizeDefinitions()) return;
      if (active() && !intact(minecraft)) { cancel(minecraft, owner); return; }
      system.tick(now);
      if (profile.equals(SessionProfiles.NONE)) return;
      ResourceLocation published = ClientSessionSystem.instance().previews().buildingProfile();
      if (profile.equals(published)) acknowledged = true;
      else if (acknowledged || now - startedAt > 5_000_000_000L) { cancel(minecraft, owner); return; }
      if (release != null && acknowledged) {
         var draft = ClientSessionSystem.instance().previews().buildingSubmission().orElse(null);
         if (draft == null) return;
         var data = draft.data();
         var geometry = new QuickShapeCandidateContext(data.points(), data.polygonClosed(), data.faceBaseOffset(),
            data.volumeBaseOffset(), data.perpendicularAnchor(), data.freeScrollOffset(), data.modes(), data.ctrlHeld());
         var candidate = geometry.resolve(release.hit(), release.eye(), release.view());
         var path = new io.github.fastformer.client.input.gesture.PathClosePress(false, false, candidate, false, release.occurredAt());
         owner.postQuickShapePointer(new io.github.fastformer.client.quickshape.input.QuickShapePointerPress(
            draft, path, release.eye(), release.view(), data.ctrlHeld(), true));
         release = null;
         reset();
         owner.idleEntry.clear();
      }
   }

   boolean active() { return system != null && system.active() || !profile.equals(SessionProfiles.NONE); }

   void reset() {
      if (system != null) system.reset();
      profile = SessionProfiles.NONE;
      release = null;
      acknowledged = false;
      startedAt = 0;
      clearCapturedContext();
   }

   private BindingSystem system(Minecraft minecraft, ClientInputSession owner) {
      if (system == null) {
         var configuration = BindingConfiguration.instance();
         system = configuration.createSystem(() -> {
            owner.cancelBoundPointerInput();
            owner.clearEntryInput();
            if (active()) cancel(minecraft, owner);
         });
      }
      return system;
   }

   private boolean intact(Minecraft minecraft) {
      return minecraft.player != null && minecraft.player == player && minecraft.level == environment
         && minecraft.screen == null && minecraft.isWindowActive() && minecraft.getConnection() != null
         && ItemStack.isSameItemSameComponents(item, minecraft.player.getMainHandItem());
   }

   private void clearCapturedContext() { environment = null; player = null; item = null; physicalButton = -1; }

   void cancel(Minecraft minecraft, ClientInputSession owner) {
      boolean started = !profile.equals(SessionProfiles.NONE);
      int capturedButton = physicalButton;
      reset();
      owner.idleEntry.clear();
      owner.releaseQuickShapeButton(capturedButton);
      minecraft.options.keyUse.setDown(false);
      if (started) {
         owner.cancel();
         if (minecraft.getConnection() != null && ServerGateway.supports(minecraft, QuitFastPlacePayload.TYPE))
            ServerGateway.send(QuitFastPlacePayload.INSTANCE);
      }
   }

   private BindingContext context(Minecraft minecraft, boolean idle, boolean heldRelease) {
      Map<String, JsonElement> facts = new LinkedHashMap<>();
      facts.put("session", new JsonPrimitive(idle ? "fastformer:idle" : "fastformer:quick_shape"));
      facts.put("profile", new JsonPrimitive(profile.toString()));
      facts.put("screen", new JsonPrimitive(minecraft.screen != null));
      facts.put("range", new JsonPrimitive("near"));
      facts.put("aiming", new JsonPrimitive("block"));
      facts.put("tinker_consumes", new JsonPrimitive(false));
      facts.put("double_second_press", new JsonPrimitive(false));
      facts.put("in_fluid", new JsonPrimitive(minecraft.player.isInWater()));
      facts.put("through_fluids", new JsonPrimitive(InteractionRaycastRules.passesThroughFluids(minecraft.player, false)));
      facts.put("sneaking", new JsonPrimitive(minecraft.player.isShiftKeyDown()));
      JsonArray items = new JsonArray();
      items.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(minecraft.player.getMainHandItem().getItem()).toString());
      minecraft.player.getMainHandItem().getTags().forEach(tag -> items.add("#" + tag.location()));
      if (io.github.fastformer.common.placement.context.PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())) items.add("#fastformer:placeable");
      facts.put("items", items);
      facts.put("modifiers", new JsonArray());
      JsonArray tags = new JsonArray();
      if (minecraft.hitResult instanceof BlockHitResult hit) minecraft.level.getBlockState(hit.getBlockPos()).getTags()
         .forEach(tag -> tags.add("#" + tag.location()));
      facts.put("aiming_tag", tags);
      return new BindingContext(facts);
   }

   private static BlockPos workspaceCandidate(Minecraft minecraft) {
      var operations = ClientSessionSystem.instance().operations();
      var intent = operations.pointer().resolve(InteractionContext.capture(minecraft)).orElse(null);
      return operations.selectionEdits().remoteSelectionPointing() ? operations.remotePointer().operationCandidatePoint()
         : intent instanceof OperationInteractionIntent.CreateSelection create ? create.point() : null;
   }

   private static boolean confirm(Minecraft minecraft, ClientInputSession owner, KeyboardInputSnapshot input, ConfirmationCommand command) {
      switch (command) {
         case RESTORE_SESSIONS -> {
            ClientSessionSystem.instance().operations().confirmReconnectRestore();
            ClientSessionSystem.instance().previewUpdates().confirmReconnectRestore();
         }
         case CONFIRM_SELECTION -> {
            BlockPos candidate = workspaceCandidate(minecraft);
            finishConfirmationGesture(owner);
            var result = ClientSessionSystem.instance().operations().confirmSelection(candidate);
            if (!result.accepted()) ClientInteractionFeedback.showWorkspaceSubmitFailure(minecraft, result);
         }
         case SUBMIT_QUICK_SHAPE -> {
            finishConfirmationGesture(owner);
            if (!QuickShapeSubmissionController.submit(minecraft, owner.quickShapeSubmission, input.quickShapeSubmission()))
               ClientInteractionFeedback.show(minecraft, "fastformer.message.placement_confirm_failed");
         }
         case CONFIRM_PLACEMENT -> {
            finishConfirmationGesture(owner);
            if (!ClientPlacementRouter.confirm(minecraft))
               ClientInteractionFeedback.show(minecraft, "fastformer.message.placement_confirm_failed");
         }
      }
      return true;
   }

   private static void finishConfirmationGesture(ClientInputSession owner) {
      owner.undoPress.cancel();
      owner.undoPressCaptured = false;
      if (owner.modifier.held()) owner.modifier.consume();
   }

   private boolean cancel(Minecraft minecraft, ClientInputSession owner, KeyboardInputSnapshot input, CancellationCommand command) {
      if (input == null || input.cancellation() == null) return false;
      switch (command) {
         case CANCEL_BINDING -> cancel(minecraft, owner);
         case CLEAR_ENTRY -> owner.clearEntryInput();
         case REPORT_SUBMISSION_PENDING -> ClientInteractionFeedback.show(minecraft, "fastformer.message.operation_submit_pending");
         case CANCEL_SELECTION -> {
            InputWorldCleanup.cancelOperationGesture(minecraft);
            ClientSessionSystem.instance().operations().selectionEdits().cancelLastSelection();
            if (ServerGateway.supports(minecraft, QuitFastPlacePayload.TYPE)) ServerGateway.send(QuitFastPlacePayload.INSTANCE);
         }
         case REQUEST_CANCEL -> ClientModeKeyInput.cancelActiveSession(minecraft, input.cancellation().dismissOperationRestore(),
            input.cancellation().dismissPreviewRestore());
      }
      return true;
   }

   private static boolean sendWorldHistory(Minecraft minecraft, ClientInputSession owner, WorkspaceCommand command) {
      if (minecraft.getConnection() == null) return false;
      if (owner.routing.dispatch(InputKind.KEY) == Dispatch.OPERATION) InputWorldCleanup.cancelOperationGesture(minecraft);
      return WorldHistoryCommandDispatcher.send(minecraft, command == WorkspaceCommand.WORLD_UNDO ? 90 : 89);
   }

   private BindingCommands commands(Minecraft minecraft, ClientInputSession owner, IdlePressSnapshot press, boolean control, long now) {
      return commands(minecraft, owner, press, control, now, null);
   }

   private BindingCommands commands(Minecraft minecraft, ClientInputSession owner, IdlePressSnapshot press, boolean control,
      long now, KeyboardInputSnapshot input) {
      return commands(minecraft, owner, press, control, now, input, -1);
   }

   private BindingCommands commands(Minecraft minecraft, ClientInputSession owner, IdlePressSnapshot press, boolean control,
      long now, KeyboardInputSnapshot input, int sourceButton) {
      return new BindingCommands() {
         public boolean cancellation(CancellationCommand command) { return BindingInputController.this.cancel(minecraft, owner, input, command); }
         public boolean historyConflict(HistoryConflictCommand command) {
            return HistoryConflictConfirmation.respond(minecraft, command);
         }
         public boolean confirmation(io.github.fastformer.client.interaction.binding.action.ConfirmationCommand command) {
            return input != null && confirm(minecraft, owner, input, command);
         }
         public boolean workspace(WorkspaceCommand command) {
            var operations = ClientSessionSystem.instance().operations();
            switch (command) {
               case COPY -> ClientInteractionFeedback.showCopyResult(minecraft, operations.clipboard().copy());
               case PASTE -> ClientInteractionFeedback.showPasteResult(minecraft, operations.clipboard().paste(minecraft));
               case TOGGLE_SELECT_ALL -> operations.toggleAllWorkspaceParts();
               case DUPLICATE -> ClientInteractionFeedback.showPasteResult(minecraft, operations.clipboard().duplicate());
               case ROTATE_SELECTION -> operations.transforms().transformSelected(minecraft, false);
               case FLIP_SELECTION -> operations.transforms().transformSelected(minecraft, true);
               case REPORT_SUBMISSION_PENDING -> ClientInteractionFeedback.show(minecraft, "fastformer.message.operation_submit_pending");
               case DELETE_SELECTION -> operations.deletions().delete(workspaceCandidate(minecraft));
               case REMOVE_SELECTED -> operations.selectionEdits().removeSelectedParts();
               case UNDO -> operations.selectionEdits().undo();
               case WORLD_UNDO, WORLD_REDO -> {
                  return sendWorldHistory(minecraft, owner, command);
               }
            }
            return true;
         }
         public boolean start(ResourceLocation requestedProfile) {
            if (press == null) return false;
            var constraints = SessionProfiles.find(minecraft.player.registryAccess(), requestedProfile).orElse(null);
            if (constraints == null) return false;
            boolean embedded = constraints.entry().anchor().equals("embedded");
            if (!QuickShapeStartController.capture(minecraft, owner, press.outline(), 0, sourceButton, embedded,
               InteractionRaycastRules.VANILLA_OUTLINE.throughFluids(), press.eye(), press.view(), requestedProfile)) return false;
            profile = requestedProfile;
            startedAt = System.nanoTime();
            owner.idleEntry.clear();
            return true;
         }
         public boolean point() { return QuickShapeMouseInputController.capture(minecraft, owner, 1, 1, now, false); }
         public boolean middleConfirm() {
            if (profile.equals(SessionProfiles.NONE)) return QuickShapeMouseInputController.capture(minecraft, owner, 1, 2, now, false);
            Vec3 eye = minecraft.player.getEyePosition();
            Vec3 view = minecraft.player.getViewVector(1);
            release = new Release(eye, view, LongRangeBlockRaycast.clipForPlacement(minecraft.level, minecraft.player, eye, view).hit(), now);
            return true;
         }
         public boolean undoPoint() { return QuickShapeMouseInputController.captureUndo(minecraft, owner, 1, 0, now); }
         public boolean cancel() { BindingInputController.this.cancel(minecraft, owner); return true; }
         public boolean cycleMode() { return false; }
         public boolean tool(io.github.fastformer.client.interaction.binding.action.ToolCommand command) {
            switch (command) {
               case TOGGLE_REPLACEMENT -> io.github.fastformer.client.placement.QuickReplaceMode.toggle(minecraft);
               case BEGIN_REPLACEMENT -> io.github.fastformer.client.placement.QuickReplaceMode.beginStroke(minecraft);
               case APPLY_REPLACEMENT -> io.github.fastformer.client.placement.QuickReplaceMode.replace(minecraft);
               case END_REPLACEMENT -> io.github.fastformer.client.placement.QuickReplaceMode.finishStroke(minecraft);
               case HISTORY_UNDO, HISTORY_REDO -> {
                  io.github.fastformer.client.placement.QuickReplaceMode.finishStroke(minecraft);
                  minecraft.options.keyUse.setDown(false);
                  WorldHistoryCommandDispatcher.send(minecraft, command ==
                     io.github.fastformer.client.interaction.binding.action.ToolCommand.HISTORY_UNDO ? 90 : 89);
               }
               case START_FILL -> owner.smartFill().start(minecraft);
               case CONFIRM_FILL -> owner.smartFill().confirm(minecraft);
               case CAPTURE_SWAP_KEY -> {
                  while (minecraft.options.keySwapOffhand.consumeClick()) { }
                  return false;
               }
            }
            return true;
         }
         public void replayPress(long occurredAtNanos) {
            if (press == null || !intact(minecraft)) return;
            if (IdleSessionEntryController.replay(minecraft, owner, sourceButton, occurredAtNanos, control, press)) return;
            HitResult previous = minecraft.hitResult;
            var entity = minecraft.crosshairPickEntity;
            try {
               minecraft.hitResult = press.outline();
               minecraft.crosshairPickEntity = null;
               io.github.fastformer.client.placement.ClientForcedPlacement.input(minecraft.player, 1);
               ((io.github.fastformer.client.mixin.MinecraftUseInvoker)(Object)minecraft).fastformer$startUseItem();
            } finally {
               io.github.fastformer.client.placement.ClientForcedPlacement.input(minecraft.player, 0);
               minecraft.hitResult = previous;
               minecraft.crosshairPickEntity = entity;
               minecraft.options.keyUse.setDown(false);
            }
         }
      };
   }

   private record Snapshot(OperationContext operations, SelectionTool tool, OperationInteractionIntent target,
      BlockPos world, BlockPos remove, BlockPos add, boolean alt, boolean control, SelectionBindingContext context,
      RemoteSnapshot gizmo, RemoteSnapshot shrink, RemoteSnapshot expand, RemoteSnapshot identity,
      OperationPointDragPress leftPoint, OperationPointDragPress rightPoint,
      OperationPointCommandPress select, OperationPointCommandPress edge, RemoteSelectionPointRequest next,
      RemoteSelectionPointRequest extra) { }


   /** Builds the binding port around the input owner; mutable captures never cross it. */
   static io.github.fastformer.client.interaction.binding.tool.SelectionInputController.Host selectionHost(
      Minecraft minecraft, ClientInputSession session) {
      return selectionHost(minecraft, session, true);
   }

   private static io.github.fastformer.client.interaction.binding.tool.SelectionInputController.Host selectionHost(
      Minecraft minecraft, ClientInputSession session, boolean physicalMapping) {
      return new io.github.fastformer.client.interaction.binding.tool.SelectionInputController.Host() {
         public void synchronize() { session.bindings.synchronizeShapeDefinitions(minecraft, session); }
         public boolean ownsPointer(int button) {
            return session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.OPERATION
               || session.selectionInput.owns(button);
         }
         public boolean allowsInteraction() {
            return minecraft.player != null && minecraft.screen == null
               && session.routing.dispatch(ClientInputStateMachine.InputKind.INTERACTION) == ClientInputStateMachine.Dispatch.OPERATION;
         }
         public SelectionBindingInput.Result interaction(int button) {
            return physicalMapping ? session.selectionInput.interaction(button) : null;
         }
         public io.github.fastformer.client.interaction.binding.tool.SelectionInputController.FrozenPress freeze(
            int button, long time, boolean press) {
            var value = snapshot(minecraft, session, button, time, press);
            boolean[] released = {false};
            return new io.github.fastformer.client.interaction.binding.tool.SelectionInputController.FrozenPress(
               value.context(), commands(minecraft, session, button, time, value, released), () -> released[0]);
         }
         public BindingAction.Result signal(String name, String gesture,
            io.github.fastformer.client.interaction.binding.context.BindingContext context, BindingCommands commands) {
            return session.bindings.onShapeSignal(minecraft, session, name, gesture, context, commands);
         }
         public void pressed(int button, SelectionBindingInput.Result result) { session.selectionInput.pressed(button, result); }
         public void finishRelease(int button, long time, boolean handled) {
            OperationDragController.finishSelectionPhysicalRelease(session, button, time, handled,
               minecraft.player.getEyePosition(), minecraft.player.getViewVector(1));
         }
      };
   }

   static SelectionBindingInput.Result selectionInteraction(Minecraft minecraft, ClientInputSession session, int logicalButton) {
      if (logicalButton < 0 || logicalButton > 2 || minecraft.player == null || minecraft.screen != null)
         return new SelectionBindingInput.Result(false, false);
      var mapping = switch (logicalButton) {
         case 0 -> minecraft.options.keyAttack;
         case 1 -> minecraft.options.keyUse;
         default -> minecraft.options.keyPickItem;
      };
      int button = mapping.getKey().getType() == InputConstants.Type.MOUSE ? mapping.getKey().getValue() : logicalButton;
      return io.github.fastformer.client.interaction.binding.tool.SelectionInputController.interactionBindings(
         selectionHost(minecraft, session, mapping.matchesMouse(button)), button);
   }

   private static Snapshot snapshot(Minecraft minecraft, ClientInputSession session, int button, long time, boolean press) {
      var operations = ClientSessionSystem.instance().operations();
      var tool = ClientSessionSystem.instance().selectionSession().selectionTool();
      var target = operations.pointer().resolve(InteractionContext.capture(minecraft)).orElse(null);
      boolean alt = ModifierCommandDispatcher.physicalAltDown(minecraft, false) || session.modifier.held();
      boolean control = ModifierCommandDispatcher.physicalCtrlDown(minecraft, false);
      var worldHit = tool.worldSampling() ? SmartSelectionEditView.worldTarget(minecraft) : longRangeSelectionBlockHit(minecraft);
      BlockPos world = worldHit == null ? null : worldHit.getBlockPos();
      var member = tool.worldSampling() ? SmartSelectionEditView.target(minecraft, alt) : null;
      BlockPos remove = member == null ? null : member.pick().position();
      BlockPos add = tool.worldSampling() ? SmartSelectionEditView.additionTarget(minecraft, member) : null;
      var gizmoHit = OperationDragController.gizmoHit(minecraft);
      GeometryGizmoDrag confirmed = press && operations.operationSelectionReady() && gizmoHit != null
         ? GeometryDragController.captureConfirmedOperation(minecraft, operations.remotePointer().operationGizmo(), gizmoHit, button) : null;
      var unconfirmed = press && !operations.operationSelectionReady() ? OperationDragController.captureGizmo(minecraft, button) : null;
      var gizmo = remote(operations, button, unconfirmed, confirmed, null, false);
      var shrink = remote(operations, button, press ? OperationDragController.captureFace(minecraft, button, -1, time) : null, null, null, false);
      var expand = remote(operations, button, press ? OperationDragController.captureFace(minecraft, button, 1, time) : null, null, null, false);
      var leftPoint = press ? OperationPointInputController.capturePress(minecraft, session, button, 0, time).orElse(null) : null;
      var rightPoint = press ? OperationPointInputController.capturePress(minecraft, session, button, 1, time).orElse(null) : null;
      var pointer = operations.remotePointer();
      int pointIndex = pointer.operationPointUnderCrosshairIndex();
      boolean point = operations.operationPrism() && session.operationDrag == null && session.operationPointDrag == null
         && pointIndex >= 0 && pointer.operationPointCenter(pointIndex) != null;
      OperationPointCommandPress select = null, edge = null;
      if (press && operations.remoteSelectionIdentity() != null) {
         if (pointIndex >= 0) select = OperationPointCommandPress.select(time);
         if (pointer.operationPrismEdgeInsertion() != null) edge = OperationPointCommandPress.insert(time);
      }
      var facts = EnumSet.noneOf(Fact.class);
      put(facts, Fact.VANILLA_POINTER, InteractionContext.vanillaOwnsPointer(minecraft));
      put(facts, Fact.POINTER_CAPTURED, session.selectionInput.owns(button));
      put(facts, Fact.GESTURE_BUSY, InputWorldCleanup.pointerGestureInFlight());
      put(facts, Fact.LOCAL_ACTIVE, operations.active());
      put(facts, Fact.DRAFT_ACTIVE, operations.selectionEdits().selectionDraftActive());
      put(facts, Fact.SESSION_ACTIVE, operations.selectionSessionActive());
      put(facts, Fact.CAN_CREATE, operations.selectionEdits().canStartSelectionDraft());
      put(facts, Fact.REMOTE_POINTING, operations.selectionEdits().remoteSelectionPointing());
      put(facts, Fact.UNDO_PREVIOUS_POINT, tool.undoPreviousPoint());
      put(facts, Fact.WORLD_SAMPLING, tool.worldSampling());
      put(facts, Fact.SAMPLING_EDITING, operations.selectionEdits().smartEditing());
      put(facts, Fact.GIZMO_TARGET, target instanceof OperationInteractionIntent.Gizmo);
      put(facts, Fact.FACE_TARGET, target instanceof OperationInteractionIntent.Face);
      put(facts, Fact.PART_TARGET, target instanceof OperationInteractionIntent.Part part && part.partId() > 0);
      put(facts, Fact.CREATE_TARGET, target instanceof OperationInteractionIntent.CreateSelection);
      put(facts, Fact.NO_TARGET, target == null);
      put(facts, Fact.CREATE_ALLOWED, session.routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION) == ClientInputStateMachine.Dispatch.OPERATION);
      put(facts, Fact.WORLD_HIT, world != null);
      put(facts, Fact.REMOVE_HIT, remove != null);
      put(facts, Fact.ADD_HIT, add != null);
      put(facts, Fact.REMOTE_CUBOID, operations.operationCuboid());
      put(facts, Fact.REMOTE_PRISM, operations.operationPrism());
      put(facts, Fact.SELECTION_READY, operations.operationSelectionReady());
      put(facts, Fact.CONFIRMED, operations.operationSelectionConfirmed());
      put(facts, Fact.ADJUSTMENT_STARTED, operations.operationAdjustmentStarted());
      put(facts, Fact.ALT, alt);
      put(facts, Fact.ADJUST, operations.operationSelectionReady() && ModifierCommandDispatcher.operationAdjustModifierHeld());
      put(facts, Fact.LEFT_CHANNEL, ServerGateway.supports(minecraft, UndoFastPlacePayload.TYPE) || ServerGateway.supports(minecraft, GeometryInteractionPayload.TYPE));
      put(facts, Fact.POINT_CHANNEL, ServerGateway.supports(minecraft, OperationPointPayload.TYPE));
      put(facts, Fact.POINT_DRAG_CHANNEL, ServerGateway.supports(minecraft, OperationPointDragPayload.TYPE));
      put(facts, Fact.EDGE_CHANNEL, ServerGateway.supports(minecraft, OperationInsertPointPayload.TYPE));
      put(facts, Fact.REMOTE_GIZMO_TARGET, gizmoHit != null);
      put(facts, Fact.REMOTE_GIZMO_READY, confirmed != null || unconfirmed != null);
      put(facts, Fact.SELECT_CHANNEL, ServerGateway.supports(minecraft, OperationSelectPointPayload.TYPE));
      put(facts, Fact.FACE_DRAG_READY, shrink.drag() != null);
      put(facts, Fact.POINT_TARGET, point);
      put(facts, Fact.POINT_DRAG_READY, point && facts.contains(Fact.POINT_DRAG_CHANNEL));
      put(facts, Fact.EDGE_TARGET, operations.operationPrism() && pointer.operationPrismEdgeInsertion() != null);
      put(facts, Fact.CANDIDATE, pointer.operationCandidatePoint() != null);
      put(facts, Fact.WORLD_TARGET, pointer.operationPointerTarget().kind() == OperationPointerKind.WORLD);
      put(facts, Fact.REMOTE_FACE_TARGET, pointer.operationPointerTarget().kind() == OperationPointerKind.FACE);
      put(facts, Fact.PRISM_BASE_OPEN, pointer.operationPrismBaseOpen());
      put(facts, Fact.CLICK_CAPTURED, session.operationClick.owns(button));
      put(facts, Fact.DRAG_CAPTURED, session.operationDrag != null && session.operationDrag.mouseButton() == button);
      put(facts, Fact.UNDO_CAPTURED, session.selectionUndoButton == button);
      put(facts, Fact.PATH_CHANNEL, ServerGateway.supports(minecraft, ClosePathPayload.TYPE));
      var role = pointer.operationNeedsFirst() ? OperationPointPayload.Role.FIRST
         : operations.operationCuboid() || pointer.operationNeedsSecond() ? OperationPointPayload.Role.SECOND : OperationPointPayload.Role.EXTRA;
      var next = RemoteSelectionPointDispatcher.capture(role);
      var extra = RemoteSelectionPointDispatcher.capture(OperationPointPayload.Role.EXTRA);
      var close = remote(operations, button, null, null, press ? PathCloseInputDispatcher.capture(false, time) : null, false);
      return new Snapshot(operations, tool, target, world, remove, add, alt, control,
         new SelectionBindingContext(tool.id().toString(), "fastformer:" + tool.geometry().name().toLowerCase(Locale.ROOT), facts),
         gizmo, shrink, expand, close, leftPoint, rightPoint, select, edge, next, extra);
   }

   private static void put(EnumSet<Fact> facts, Fact fact, boolean value) { if (value) facts.add(fact); }

   private static RemoteSnapshot remote(OperationContext operations, int button, OperationDrag drag,
      GeometryGizmoDrag gizmo, PathClosePress path, boolean undo) {
      return new RemoteSnapshot(operations.interactionScene().owner(), operations.remoteSelectionIdentity(),
         operations.remoteSelectionRevision(), operations.remoteSelectionCallbackScope(), button, drag, gizmo, path, undo);
   }

   private static BindingCommands commands(Minecraft minecraft, ClientInputSession session, int button, long time,
      Snapshot snapshot, boolean[] released) {
      return new BindingCommands() {
         public boolean selection(SelectionBindingCommand command, int durationMillis) {
            if (command != SelectionBindingCommand.RELEASE && command != SelectionBindingCommand.END_UNDO)
               session.supersedeSelectionCaptures();
            return switch (command) {
               case DRAFT_LEFT -> draft(session, snapshot, 0, button, time, durationMillis);
               case DRAFT_RIGHT -> draft(session, snapshot, 1, button, time, durationMillis);
               case DRAFT_MIDDLE -> draft(session, snapshot, 2, button, time, durationMillis);
               case POINT_LEFT -> BindingInputController.point(session, snapshot, 0, button, time, durationMillis);
               case POINT_RIGHT -> BindingInputController.point(session, snapshot, 1, button, time, durationMillis);
               case POINT_MIDDLE -> BindingInputController.point(session, snapshot, 2, button, time, durationMillis);
               case POINTER_SHRINK -> pointer(session, snapshot, button, time, -1);
               case POINTER_EXPAND -> pointer(session, snapshot, button, time, 1);
               case GIZMO -> pointer(session, snapshot, button, time, 0);
               case REMOTE_GIZMO -> queueRemote(session, snapshot.gizmo());
               case REMOTE_FACE_SHRINK -> queueRemote(session, snapshot.shrink());
               case REMOTE_FACE_EXPAND -> queueRemote(session, snapshot.expand());
               case REMOTE_POINT_LEFT -> remotePoint(session, snapshot.leftPoint(), button);
               case REMOTE_POINT_RIGHT -> remotePoint(session, snapshot.rightPoint(), button);
               case REMOTE_SELECT -> remoteCommand(session, snapshot.select(), button);
               case REMOTE_EDGE -> remoteCommand(session, snapshot.edge(), button);
               case REMOTE_NEXT -> next(session, snapshot, button, time, durationMillis, false);
               case REMOTE_EXTRA -> next(session, snapshot, button, time, durationMillis, true);
               case REMOTE_CLOSE -> queueRemote(session, snapshot.identity());
               case CAPTURE -> { session.selectionInput.capture(button); yield true; }
               case BEGIN_UNDO -> {
                  session.selectionInput.capture(button);
                  session.selectionUndo.press(time);
                  session.selectionUndoButton = button;
                  var id = snapshot.identity();
                  session.selectionUndoPress = new RemoteSnapshot(id.owner(), id.selection(), id.revision(), id.scope(), button, null, null, null, true);
                  yield true;
               }
               case END_UNDO -> {
                  var press = session.selectionUndoPress;
                  if (press != null && session.selectionUndoButton == button && session.selectionUndo.release(time, durationMillis * 1_000_000L))
                     session.postSelectionPointer(new SelectionPointerEvent.RemotePress(1L, press));
                  OperationDragController.clearSelectionUndo(session);
                  released[0] = true;
                  yield OperationDragController.releaseSelection(minecraft, session, button, time, false);
               }
               case RELEASE -> { released[0] = true; yield OperationDragController.releaseSelection(minecraft, session, button, time, false); }
            };
         }
         public void replayPress(long time) { throw new IllegalStateException("Selection input cannot replay a physical press"); }
      };
   }

   private static boolean draft(ClientInputSession session, Snapshot snapshot, int logical, int physical, long time, int duration) {
      BlockPos point = snapshot.tool().worldSampling() ? logical == 0 ? snapshot.remove()
         : logical == 1 && snapshot.alt() ? snapshot.add() : snapshot.world()
         : snapshot.target() instanceof OperationInteractionIntent.CreateSelection create && !snapshot.alt() ? create.point() : snapshot.world();
      if (logical == 0 && snapshot.tool().undoPreviousPoint()) point = null;
      session.selectionInput.capture(physical);
      if (point == null && !snapshot.alt() && !(logical == 0 && snapshot.tool().undoPreviousPoint())) return true;
      session.captureSelectionPress(new SelectionDraftPress(snapshot.identity().owner(), snapshot.tool().geometry(), logical,
         point, snapshot.alt(), snapshot.control(), time, snapshot.tool().id()), physical, duration);
      return true;
   }

   private static boolean point(ClientInputSession session, Snapshot snapshot, int logical, int physical, long time, int duration) {
      var workspace = snapshot.operations().workspace();
      int part = logical == 2 ? workspace.selections().topPart().map(ClientSelectionPart::id).orElse(0) : workspace.activeId();
      session.selectionInput.capture(physical);
      if (snapshot.tool().geometry() == OperationSelectionMode.CUBOID
         && !session.acceptCuboidPointPress(logical, physical, snapshot.world(), time, duration)) return true;
      session.captureSelectionPress(new SelectionPointPress(snapshot.identity().owner(), part, workspace.interactionId(part),
         logical, snapshot.control(), snapshot.world(), time), physical);
      return true;
   }

   private static boolean pointer(ClientInputSession session, Snapshot snapshot, int button, long time, int steps) {
      session.selectionInput.capture(button);
      SelectionPointerPress.capture(snapshot.target(), button, snapshot.control(), steps,
         snapshot.operations().interactionScene(), snapshot.operations().workspace(), time)
         .ifPresent(press -> session.captureSelectionPress(press, snapshot.alt()));
      return true;
   }

   private static boolean queueRemote(ClientInputSession session, RemoteSnapshot press) {
      session.selectionInput.capture(press.button());
      session.captureSelectionPress(press);
      return true;
   }

   private static boolean remotePoint(ClientInputSession session, OperationPointDragPress press, int button) {
      session.selectionInput.capture(button);
      if (press != null) session.captureOperationPointDrag(press);
      return true;
   }

   private static boolean remoteCommand(ClientInputSession session, OperationPointCommandPress press, int button) {
      session.selectionInput.capture(button);
      if (press != null) session.captureOperationPointCommand(press);
      return true;
   }

   private static boolean next(ClientInputSession session, Snapshot snapshot, int button, long time, int duration, boolean extra) {
      session.selectionInput.capture(button);
      BlockPos target = snapshot.target() instanceof OperationInteractionIntent.CreateSelection create ? create.point() : snapshot.world();
      if (!extra && snapshot.context().facts().contains(Fact.REMOTE_CUBOID)
         && !session.acceptCuboidPointPress(1, button, target, time, duration)) return true;
      session.postRemoteSelectionPoint(extra ? snapshot.extra() : snapshot.next());
      return true;
   }


   static BlockHitResult longRangeSelectionBlockHit(Minecraft minecraft) {
      if (minecraft.level == null || minecraft.player == null) return null;
      var hit = ClientSessionSystem.instance().inputSession().pointer().selection(minecraft.player, ModifierCommandDispatcher.physicalAltDown(minecraft, false)).hit();
      return hit.getType() == HitResult.Type.BLOCK ? hit : null;
   }


}
