package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.context.SelectionBindingContext;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import io.github.fastformer.client.operation.selection.SelectionToolPreference;
import io.github.fastformer.client.operation.selection.tool.SelectionTools;

import io.github.fastformer.client.operation.controller.OperationContext;
import io.github.fastformer.client.interaction.selectiongesture.SelectionGesturePress;
import io.github.fastformer.client.preview.frame.ModifierReticleMode;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.client.operation.input.SelectionPointerPress;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.InputEvent.Key;
import net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre;
import net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent;
import io.github.fastformer.protocol.s2c.placement.IdleInteractionSnapshotPayload;
import io.github.fastformer.protocol.s2c.placement.IdleSelectionEntryResultPayload;
import io.github.fastformer.protocol.s2c.placement.PlacementActionAckPayload;
import io.github.fastformer.protocol.s2c.preview.SmartFillPreviewPayload;
import java.util.UUID;
import java.util.function.Consumer;

/** Event adapter and external input API; concrete capabilities own all input decisions. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class FastPlaceClientInput {
   private FastPlaceClientInput() {}

   static ClientInputSession inputSession() {
      return ClientSessionSystem.instance().inputSession();
   }

   public static ClientInputSession currentSession() { return inputSession(); }

   public static void endWorldSession() { InputWorldCleanup.endWorldSession(); }

   public static boolean beginPlacementRequest(long requestId) { return InputWorldCleanup.beginPlacementRequest(requestId); }

   public static boolean beginStartPlacementRequest(long requestId) { return InputWorldCleanup.beginStartPlacementRequest(requestId); }

   public static boolean awaitsPlacementRequest(long requestId) { return InputWorldCleanup.awaitsPlacementRequest(requestId); }

   public static void acknowledgePlacementRequest( PlacementActionAckPayload payload ) { InputWorldCleanup.acknowledgePlacementRequest(payload); }

   public static void abortPlacementRequest(long requestId) { InputWorldCleanup.abortPlacementRequest(requestId); }

   public static boolean beginWorkspaceRequest(UUID transferId) { return InputWorldCleanup.beginWorkspaceRequest(transferId); }

   public static void acknowledgeWorkspaceRequest(UUID transferId) { InputWorldCleanup.acknowledgeWorkspaceRequest(transferId); }

   public static void abortWorkspaceRequest(UUID transferId) { InputWorldCleanup.abortWorkspaceRequest(transferId); }

   public static void expireWorkspaceRequest(UUID transferId) { InputWorldCleanup.expireWorkspaceRequest(transferId); }

   @SubscribeEvent
   public static void onKey(Key event) { ClientModeKeyInput.onKey(event); }

   static void handleKey(Minecraft minecraft, KeyboardInputSnapshot event) { ClientModeKeyInput.handleKey(minecraft, event); }

   @SubscribeEvent
   public static void onInteraction(InteractionKeyMappingTriggered event) { ClientPhysicalInputDispatcher.onInteraction(event); }

   @SubscribeEvent
   public static void onMouseButton(Pre event) { ClientPhysicalInputDispatcher.onMouseButton(event); }

   public static void receiveIdleInteraction(IdleInteractionSnapshotPayload payload) { InputWorldCleanup.receiveIdleInteraction(payload); }

   public static void receiveIdleSelectionEntry(IdleSelectionEntryResultPayload payload) { InputWorldCleanup.receiveIdleSelectionEntry(payload); }

   @SubscribeEvent
   public static void onVanillaMouseButton(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Post event) { ClientPhysicalInputDispatcher.onVanillaMouseButton(event); }

   public static void prepareTick(Minecraft minecraft) { InputWorldCleanup.prepareTick(minecraft); }

   public static void clearWorldInput(Minecraft minecraft) { InputWorldCleanup.clearWorldInput(minecraft); }

   public static void dispatchTick(Minecraft minecraft) { InputWorldCleanup.dispatchTick(minecraft); }

   public static void advanceTick(Minecraft minecraft) { InputWorldCleanup.advanceTick(minecraft); }

   @SubscribeEvent
   public static void onMouseScroll(MouseScrollingEvent event) { ScrollInputDispatcher.onMouseScroll(event); }

   public static boolean modifierHeld() { return ModifierCommandDispatcher.modifierHeld(); }

   public static boolean controlHeld() { return ModifierCommandDispatcher.controlHeld(); }

   public static boolean operationAdjustModifierHeld() { return ModifierCommandDispatcher.operationAdjustModifierHeld(); }

   public static ModifierReticleMode modifierReticleMode() { return ModifierCommandDispatcher.modifierReticleMode(); }

   public static void receiveSmartFill(SmartFillPreviewPayload payload) { InputWorldCleanup.receiveSmartFill(payload); }

   static void finishPointerGesture() { InputWorldCleanup.finishPointerGesture(); }

   static void finishPointerGesture(long token) { InputWorldCleanup.finishPointerGesture(token); }

   static void dispatchSelectionGesture(ClientInputSession input, OperationContext operations, SelectionPointerPress press) { OperationDragController.dispatchSelectionGesture(input, operations, press); }

   static SelectionGesturePress.Gizmo selectionGizmoPress(OperationInteractionIntent.Gizmo target, int button, boolean control) { return OperationDragController.selectionGizmoPress(target, button, control); }
   static boolean captureInitialSelection(int button, BlockPos point, boolean alt, boolean control, long time) {
      var session = ClientSessionSystem.instance().inputSession(); var operations = ClientSessionSystem.instance().operations();
      if (button < 0 || button > 2 || session.routing.state() != ClientInputStateMachine.State.IDLE
         || operations.active() || operations.selectionEdits().selectionDraftActive()) return false;
      if (button == 0 && SelectionTools.require(SelectionToolPreference.id()).worldSampling()) return true;
      if (point == null) return false;
      operations.selectionEdits().enterLocalSelectionSession(); session.routing.observe(ClientInputStateMachine.State.SELECTING);
      var tool = ClientSessionSystem.instance().selectionSession().selectionTool();
      session.captureSelectionPress(new SelectionDraftPress(operations.interactionScene().owner(), tool.geometry(), button, point, alt, control, time, tool.id()));
      return true;
   }

   static boolean captureSelectionEntry(BlockPos point, boolean control, long time) { return captureInitialSelection(1, point, false, control, time); }
   public static boolean canCreateSelection() { return ClientSessionSystem.instance().inputSession().canCreateSelection(); }
   static void queueRemoteSelectionPoint(OperationPointPayload.Role role) { ClientSessionSystem.instance().inputSession().postRemoteSelectionPoint(RemoteSelectionPointDispatcher.capture(role)); }
   static void handleRemoteSelectionPoint(RemoteSelectionPointRequest request, Consumer<OperationPointPayload> send) { RemoteSelectionPointDispatcher.dispatch(request, ClientSessionSystem.instance().inputSession().routing, send); }


}
