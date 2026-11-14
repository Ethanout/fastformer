package io.github.fastformer.client.input;

import io.github.fastformer.client.interaction.binding.tool.SelectionInputController;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.binding.tool.QuickReplaceBindingInput;
import io.github.fastformer.client.interaction.binding.tool.SmartFillBindingInput;
import io.github.fastformer.client.operation.input.OperationPointCommandEvent;
import io.github.fastformer.client.operation.input.OperationPointDragEvent;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import io.github.fastformer.client.placement.ClientForcedPlacement;
import io.github.fastformer.client.quickshape.input.QuickShapePointerPress;
import io.github.fastformer.client.quickshape.input.QuickShapeUndoGesture;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.protocol.c2s.placement.StartPlacementPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre;

/** Routes captured input to its owner without repeating resource decisions. */
final class ClientPhysicalInputDispatcher implements PhysicalInputSink {
   private final Minecraft minecraft;
   private final ClientInputSession session;
   ClientPhysicalInputDispatcher(Minecraft minecraft, ClientInputSession session) { this.minecraft = minecraft; this.session = session; }
   public void accept(KeyboardInputSnapshot event) { ClientModeKeyInput.handleKey(minecraft, event); }
   public void accept(ScrollInputSnapshot event) { ScrollInputDispatcher.dispatch(minecraft, session, event); }
   public void accept(SelectionPointerEvent event) { OperationDragController.dispatchSelection(minecraft, session, event); }
   public void accept(RemoteSelectionPointRequest event) { RemoteSelectionPointSender.sendIfAvailable(minecraft, session, event); }
   public void accept(PointerReleaseSnapshot event) {
      event.dispatch(session, MouseReleaseDispatcher.target(session, MouseButtonInputSemantics.RELEASE, event.button()),
         () -> MouseReleaseDispatcher.finish(minecraft, session, MouseButtonInputSemantics.RELEASE, event.button(), event.occurredAtNanos()));
   }
   public void accept(QuickShapeUndoGesture.Event event) { QuickShapeMouseInputController.dispatchUndo(minecraft, session, event); }
   public void accept(QuickShapePointerPress event) { QuickShapeMouseInputController.dispatch(minecraft, session, event); }
   public void accept(StartPlacementPayload.Target event) { QuickShapeStartController.dispatch(minecraft, session, event); }
   public void accept(GeometryGizmoCapture.Event event) { GeometryInputController.dispatchGizmo(minecraft, session, event); }
   public void accept(GeometryInputController.PointerPress event) { GeometryInputController.dispatchPointer(minecraft, session, event); }
   public void accept(OperationPointDragEvent event) { OperationPointInputController.dispatch(minecraft, session, event); }
   public void accept(OperationPointCommandEvent event) { OperationPointCommandDispatcher.dispatch(minecraft, session, event); }

   static void onInteraction(InteractionKeyMappingTriggered event) {
      var owner = ClientSessionSystem.instance().inputSession();
      long time = System.nanoTime();
      Minecraft minecraft = Minecraft.getInstance();
      if (event.isUseItem() && event.getHand() == InteractionHand.MAIN_HAND && minecraft.player != null) {
         boolean accepted = ClientForcedPlacement.accept(minecraft.player);
         if (!accepted && minecraft.hitResult instanceof BlockHitResult hit && ClientForcedPlacement.consumes(minecraft.player, event.getHand(), hit)) {
            event.setSwingHand(false); event.setCanceled(true); return;
         }
      }
      InputWorldCleanup.synchronizeInputState();
      if (owner.routing.dispatch(ClientInputStateMachine.InputKind.INTERACTION) == ClientInputStateMachine.Dispatch.BLOCKED) {
         event.setSwingHand(false); event.setCanceled(true); return;
      }
      var fill = SmartFillBindingInput.interaction(ClientModeKeyInput.ownsFillInput(owner, ClientInputStateMachine.InputKind.INTERACTION),
         ClientModeKeyInput.fillContext(minecraft, owner, false), event.isUseItem(), time, ClientModeKeyInput.toolDispatcher(minecraft, owner));
      if (fill.owns()) {
         if (fill.consumesVanilla()) { event.setSwingHand(false); event.setCanceled(true); }
         return;
      }
      var replacement = QuickReplaceBindingInput.interaction(ClientModeKeyInput.replacementContext(minecraft, owner),
         event.isUseItem(), time, ClientModeKeyInput.toolDispatcher(minecraft, owner));
      if (replacement.owns()) {
         if (replacement.consumesVanilla()) { event.setSwingHand(false); event.setCanceled(true); }
         return;
      }
      if (owner.routing.state() == ClientInputStateMachine.State.IDLE) return;
      int button = event.isAttack() ? 0 : event.isUseItem() ? 1 : event.isPickBlock() ? 2 : -1;
      var geometry = GeometryInputController.interactionBindings(minecraft, owner, button);
      if (geometry.owns()) {
         if (geometry.consumesVanilla()) { event.setSwingHand(false); event.setCanceled(true); }
         return;
      }
      var line = QuickShapeMouseInputController.interactionBindings(minecraft, owner, button);
      if (line.owns()) {
         if (line.consumesVanilla()) { event.setSwingHand(false); event.setCanceled(true); }
         return;
      }
      var selection = BindingInputController.selectionInteraction(minecraft, owner, button);
      if (selection.owns()) {
         if (selection.consumesVanilla()) { event.setSwingHand(false); event.setCanceled(true); }
         return;
      }
   }

   static void onMouseButton(Pre event) {
      var owner = ClientSessionSystem.instance().inputSession();
      if (event.isCanceled()) return;
      long time = System.nanoTime();
      Minecraft minecraft = Minecraft.getInstance();
      if (event.getAction() == MouseButtonInputSemantics.RELEASE && minecraft.options.keyUse.matchesMouse(event.getButton())) {
         minecraft.options.keyUse.setDown(false); ClientForcedPlacement.input(minecraft.player, event.getAction());
      }
      var replacement = QuickReplaceBindingInput.mouse(ClientModeKeyInput.replacementContext(minecraft, owner),
         minecraft.player != null && minecraft.screen == null && minecraft.getConnection() != null,
         minecraft.options.keyUse.matchesMouse(event.getButton()), event.getAction(), event.getButton(), time,
         ClientModeKeyInput.toolDispatcher(minecraft, owner));
      if (replacement.consumesVanilla()) event.setCanceled(true);
      if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) {
         owner.bindings.cancel(minecraft, owner); return;
      }
      if (replacement.owns()) return;
      InputWorldCleanup.synchronizeInputState();
      boolean physicalAlt = ModifierCommandDispatcher.physicalAltDown(minecraft, false);
      if (owner.bindings.onButton(minecraft, owner, event.getAction(), event.getButton(), time)) { event.setCanceled(true); return; }
      if (event.getAction() == MouseButtonInputSemantics.RELEASE && owner.cuboidPointPressGuard.capture(event.getAction(), event.getButton(), time)) {
         minecraft.options.keyUse.setDown(false);
         SelectionInputController.captureBindings(BindingInputController.selectionHost(minecraft, owner), event.getAction(), event.getButton(), time);
         event.setCanceled(true); return;
      }
      var idle = IdleSessionEntryController.captureBindings(minecraft, owner, event.getAction(), event.getButton(),
         time, physicalAlt, ModifierCommandDispatcher.physicalCtrlDown(minecraft, false));
      if (idle.owns()) {
         if (idle.consumesVanilla()) event.setCanceled(true);
         return;
      }
      var fill = SmartFillBindingInput.mouse(ClientModeKeyInput.ownsFillInput(owner, ClientInputStateMachine.InputKind.POINTER),
         ClientModeKeyInput.fillContext(minecraft, owner, event.getButton() >= 0 && event.getButton() <= 2),
         event.getAction(), event.getButton(), time, ClientModeKeyInput.toolDispatcher(minecraft, owner));
      if (fill.owns()) {
         if (fill.consumesVanilla()) event.setCanceled(true);
         return;
      }
      var geometry = GeometryInputController.captureBindings(minecraft, owner, event.getAction(), event.getButton(), time);
      if (geometry.owns()) {
         if (geometry.consumesVanilla()) event.setCanceled(true);
         return;
      }
      var line = QuickShapeMouseInputController.captureBindings(minecraft, owner, event.getAction(), event.getButton(), time, physicalAlt);
      if (line.owns()) {
         if (line.consumesVanilla()) event.setCanceled(true);
         return;
      }
      if (QuickShapeMouseInputController.capture(minecraft, owner, event.getAction(), event.getButton(), time, physicalAlt)
         || QuickShapeMouseInputController.captureUndo(minecraft, owner, event.getAction(), event.getButton(), time)) {
         event.setCanceled(true); return;
      }
      var selection = SelectionInputController.captureBindings(BindingInputController.selectionHost(minecraft, owner), event.getAction(), event.getButton(), time);
      if (selection.owns()) {
         if (selection.consumesVanilla()) event.setCanceled(true);
         return;
      }
      if (owner.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.BLOCKED) event.setCanceled(true);
   }

   static void onVanillaMouseButton(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      if (event.getAction() == MouseButtonInputSemantics.PRESS && minecraft.screen == null
         && minecraft.options.keyUse.matchesMouse(event.getButton())) ClientForcedPlacement.input(minecraft.player, event.getAction());
   }
}
