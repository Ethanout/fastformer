package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;
import io.github.fastformer.client.interaction.binding.tool.QuickReplaceBindingInput;
import io.github.fastformer.client.interaction.binding.tool.SmartFillBindingInput;
import io.github.fastformer.client.input.mouse.MouseDragReleaseSemantics;
import io.github.fastformer.client.mode.input.pointer.PointerDecision;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.input.OperationInputSemantics;
import io.github.fastformer.client.placement.ClientForcedPlacement;
import io.github.fastformer.client.operation.pointer.OperationPointerKind;
import io.github.fastformer.client.operation.pointer.OperationPointerTarget;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.protocol.c2s.geometry.GeometryInteractionPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import io.github.fastformer.protocol.c2s.placement.UndoFastPlacePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.operation.input.OperationPointCommandEvent;
import io.github.fastformer.client.operation.input.OperationPointDragEvent;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import io.github.fastformer.client.quickshape.input.QuickShapePointerPress;
import io.github.fastformer.client.quickshape.input.QuickShapeUndoGesture;
import io.github.fastformer.protocol.c2s.placement.StartPlacementPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/** Routes a captured batch without owning another copy of input state. */
final class ClientPhysicalInputDispatcher implements PhysicalInputSink {
   private final Minecraft minecraft;
   private final ClientInputSession session;
   ClientPhysicalInputDispatcher(Minecraft minecraft, ClientInputSession session) {
      this.minecraft = minecraft; this.session = session;
   }
   public void accept(KeyboardInputSnapshot event) { ClientModeKeyInput.handleKey(minecraft, event); }
   public void accept(ScrollInputSnapshot event) { ScrollInputDispatcher.dispatch(minecraft, session, event); }
   public void accept(SelectionPointerEvent event) { SelectionInputDispatcher.dispatch(minecraft, session, event); }
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
      var inputOwner = ClientSessionSystem.instance().inputSession();
      long occurredAtNanos = System.nanoTime();
      Minecraft minecraft = Minecraft.getInstance();
      if (event.isUseItem() && event.getHand() == InteractionHand.MAIN_HAND
         && minecraft.player != null) {
         boolean accepted = ClientForcedPlacement.accept(minecraft.player);
         if (!accepted && minecraft.hitResult instanceof BlockHitResult hit
            && ClientForcedPlacement.consumes(minecraft.player, event.getHand(), hit)) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
      }
      InputWorldCleanup.synchronizeInputState();
      ClientInputStateMachine.Dispatch inputRoute = inputOwner.routing.dispatch(ClientInputStateMachine.InputKind.INTERACTION);
      boolean buildingSession = inputRoute == ClientInputStateMachine.Dispatch.BUILDING;
      boolean geometrySession = inputRoute == ClientInputStateMachine.Dispatch.GEOMETRY;
      boolean operationSession = inputRoute == ClientInputStateMachine.Dispatch.OPERATION;
      // A placement or recovery task owns the world operation. Late attack/use
      // events must not reopen a selection or start a second gesture.
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
       var fill = SmartFillBindingInput.interaction(
          ClientModeKeyInput.ownsFillInput(inputOwner, ClientInputStateMachine.InputKind.INTERACTION),
          ClientModeKeyInput.fillContext(minecraft, inputOwner, false), event.isUseItem(), occurredAtNanos,
          ClientModeKeyInput.toolDispatcher(minecraft, inputOwner));
       if (fill.owns()) {
          if (fill.consumesVanilla()) {
             event.setSwingHand(false);
             event.setCanceled(true);
          }
          return;
       }
       var replacement = QuickReplaceBindingInput.interaction(ClientModeKeyInput.replacementContext(minecraft, inputOwner),
          event.isUseItem(), occurredAtNanos, ClientModeKeyInput.toolDispatcher(minecraft, inputOwner));
      if (replacement.owns()) {
         if (replacement.consumesVanilla()) {
            event.setSwingHand(false);
            event.setCanceled(true);
         }
         return;
      }
      if (inputOwner.routing.state() == ClientInputStateMachine.State.IDLE) return;
      int lineButton = event.isAttack() ? 0 : event.isUseItem() ? 1 : event.isPickBlock() ? 2 : -1;
      var line = QuickShapeMouseInputController.interactionBindings(minecraft, inputOwner, lineButton);
      if (line.owns()) {
         if (line.consumesVanilla()) {
            event.setSwingHand(false);
            event.setCanceled(true);
         }
         return;
      }
      if (inputOwner.geometryGizmoCapture.captured() && (event.isAttack() || event.isUseItem())) {
         if (inputOwner.geometryGizmoCapture.ownsInteractionButton(event.isAttack() ? 0 : 1)) {
            event.setSwingHand(false);
            event.setCanceled(true);
         }
         return;
      }
      if ((event.isAttack() || event.isUseItem())
         && inputOwner.ownsGeometryPointerButton(event.isAttack() ? 0 : 1)) {
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (operationSession && (ModifierCommandDispatcher.modifierHeld() || ModifierCommandDispatcher.physicalAltDown(minecraft, false))
         && (event.isAttack() || event.isUseItem())) {
         return;
      }
      if (event.isUseItem()
         && buildingSession
         && !InteractionContext.vanillaOwnsPointer(minecraft)) {
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (event.isAttack()
         && minecraft.player != null
         && minecraft.player.getMainHandItem().isEmpty()
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && !buildingSession
         && !geometrySession
         && ServerGateway.supports(minecraft, OperationPointPayload.TYPE)) {
         OperationInputSemantics.LeftDecision leftDecision = SelectionInputDispatcher.operationLeftDecision(SelectionInputDispatcher.pointerIntent());
         if (leftMousePressAlreadyHandled()) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (leftDecision.yieldToVanilla()) {
            return;
         }
         if (leftDecision.action() == OperationInputSemantics.LeftAction.GIZMO_DRAG) {
            inputOwner.operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (leftDecision.action() == OperationInputSemantics.LeftAction.SELECTION_UNDO
            || leftDecision.action() == OperationInputSemantics.LeftAction.ADJUSTMENT_UNDO) {
            if (!inputOwner.undoPressCaptured) {
               inputOwner.undoPress.press(System.nanoTime());
               inputOwner.undoPressCaptured = true;
            }
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (operationSession && ModifierCommandDispatcher.modifierHeld()) {
            boolean handled = !inputOwner.operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON)
               && ClientSessionSystem.instance().operations().operationSelectionReady()
               && SelectionInputDispatcher.beginOperationGizmoDrag(minecraft, 0);
            if (handled) {
               inputOwner.modifier.consume();
            }
            inputOwner.operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (ClientSessionSystem.instance().operations().operationCuboid()) {
            if (!ModifierCommandDispatcher.modifierHeld() && !InteractionContext.vanillaOwnsPointer(minecraft)) {
               OperationPointerTarget target = ClientSessionSystem.instance().operations().remotePointer().operationPointerTarget();
               if (target.kind() == OperationPointerKind.WORLD) {
                  SelectionInputDispatcher.queueCuboidPointUndo(minecraft, System.nanoTime());
                  inputOwner.operationClick.capture(0);
                  event.setSwingHand(false);
                  event.setCanceled(true);
                  return;
               }
               if (target.kind() == OperationPointerKind.FACE) {
                  if (!inputOwner.operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON) && SelectionInputDispatcher.beginOperationFaceAdjustment(minecraft, -1, 0)) {
                     inputOwner.operationClick.capture(0);
                  }
                  event.setSwingHand(false);
                  event.setCanceled(true);
                  return;
               }
            }
         }
         if (ClientSessionSystem.instance().operations().operationPrism()
            && (inputOwner.operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON)
               || ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex() >= 0)) {
            inputOwner.operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!InteractionContext.vanillaOwnsPointer(minecraft)
            && ClientSessionSystem.instance().operations().operationPrism()
            && (inputOwner.operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON) || SelectionInputDispatcher.sendOperationEdgeInsertion(minecraft))) {
            inputOwner.operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!InteractionContext.vanillaOwnsPointer(minecraft)
            && ClientSessionSystem.instance().operations().operationPrism()
            && ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint() != null
            && (inputOwner.operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON) || SelectionInputDispatcher.sendNextOperationPrismPoint(minecraft))) {
            inputOwner.operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (!operationSession
            && !InteractionContext.vanillaOwnsPointer(minecraft)
            && ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint() != null) {
            SelectionInputDispatcher.queueCuboidPointUndo(minecraft, System.nanoTime());
            inputOwner.operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
         if (operationSession && !InteractionContext.vanillaOwnsPointer(minecraft)) {
            inputOwner.operationClick.capture(0);
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
         }
      }
      if (event.isUseItem()
         && minecraft.player != null
         && minecraft.player.getMainHandItem().isEmpty()
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && inputRoute == ClientInputStateMachine.Dispatch.VANILLA
         && !InteractionContext.vanillaOwnsPointer(minecraft)
         && ServerGateway.supports(minecraft, OperationPointPayload.TYPE)
         && ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint() != null) {
         SelectionInputDispatcher.queueRemoteSelectionPoint(SelectionInputDispatcher.nextCuboidPointRole());
         event.setSwingHand(false);
         event.setCanceled(true);
         return;
      }
      if (event.isUseItem()
         && minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && geometrySession) {
         if (InteractionContext.vanillaOwnsPointer(minecraft)) {
            return;
         }
      }
      if (event.isAttack()
         && minecraft.player != null
         && minecraft.screen == null
         && minecraft.getConnection() != null
         && (buildingSession || operationSession || geometrySession)) {
         if (InteractionContext.vanillaOwnsPointer(minecraft)) {
            return;
         }
         if (geometrySession
            && ServerGateway.supports(minecraft, GeometryInteractionPayload.TYPE)) {
            if (inputOwner.ownsGeometryPointerButton(0)) {
               event.setSwingHand(false);
               event.setCanceled(true);
               return;
            }
         }
         if (!ServerGateway.supports(minecraft, UndoFastPlacePayload.TYPE)) {
            return;
         }
         // The mouse callback owns the physical press capture. This callback only
         // suppresses the vanilla interaction path.
         event.setSwingHand(false);
         event.setCanceled(true);
      }
   }

   static void onMouseButton(Pre event) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (event.isCanceled()) return;
      long occurredAtNanos = System.nanoTime();
      Minecraft minecraft = Minecraft.getInstance();
      // A change of input owner can consume release before MouseHandler clears the vanilla key.
      if (event.getAction() == MouseButtonInputSemantics.RELEASE && minecraft.options.keyUse.matchesMouse(event.getButton())) {
         minecraft.options.keyUse.setDown(false);
         ClientForcedPlacement.input(minecraft.player, event.getAction());
      }
       var replacement = QuickReplaceBindingInput.mouse(ClientModeKeyInput.replacementContext(minecraft, inputOwner),
          minecraft.player != null && minecraft.screen == null && minecraft.getConnection() != null,
          minecraft.options.keyUse.matchesMouse(event.getButton()), event.getAction(), event.getButton(), occurredAtNanos,
          ClientModeKeyInput.toolDispatcher(minecraft, inputOwner));
      if (replacement.consumesVanilla()) event.setCanceled(true);
      if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) {
         inputOwner.bindings.cancel(minecraft, inputOwner);
         return;
      }
      if (replacement.owns()) return;
      InputWorldCleanup.synchronizeInputState();
      boolean physicalAlt = ModifierCommandDispatcher.physicalAltDown(minecraft, false);
      if (inputOwner.bindings.onButton(minecraft, inputOwner, event.getAction(), event.getButton(), occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      if (event.getAction() == MouseButtonInputSemantics.RELEASE
         && inputOwner.cuboidPointPressGuard.capture(event.getAction(), event.getButton(), occurredAtNanos)) {
         minecraft.options.keyUse.setDown(false);
         event.setCanceled(true);
         return;
      }
      if (IdleSessionEntryController.capture(minecraft, inputOwner, event.getAction(), event.getButton(),
         occurredAtNanos, physicalAlt, ModifierCommandDispatcher.physicalCtrlDown(minecraft, false))) {
         event.setCanceled(true);
         return;
      }
       var fill = SmartFillBindingInput.mouse(
          ClientModeKeyInput.ownsFillInput(inputOwner, ClientInputStateMachine.InputKind.POINTER),
          ClientModeKeyInput.fillContext(minecraft, inputOwner, event.getButton() >= 0 && event.getButton() <= 2),
          event.getAction(), event.getButton(), occurredAtNanos, ClientModeKeyInput.toolDispatcher(minecraft, inputOwner));
       if (fill.owns()) {
          if (fill.consumesVanilla()) event.setCanceled(true);
          return;
       }
      if (GeometryInputController.queueGizmo(minecraft, inputOwner, event.getAction(), event.getButton())) {
         event.setCanceled(true);
         return;
      }
      if (GeometryInputController.queueInteraction(minecraft, inputOwner, event.getAction(), event.getButton())) {
         event.setCanceled(true);
         return;
      }
      if (inputOwner.geometryGizmoCapture.hasPhysicalPress()) return;
      if (GeometryInputController.queuePath(minecraft, inputOwner, event.getAction(), event.getButton(), occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      var line = QuickShapeMouseInputController.captureBindings(minecraft, inputOwner, event.getAction(), event.getButton(),
         occurredAtNanos, physicalAlt);
      if (line.owns()) {
         if (line.consumesVanilla()) event.setCanceled(true);
         return;
      }
      if (QuickShapeMouseInputController.capture(minecraft, inputOwner, event.getAction(), event.getButton(),
         occurredAtNanos, physicalAlt)) {
         event.setCanceled(true);
         return;
      }
      if (QuickShapeMouseInputController.captureUndo(minecraft, inputOwner, event.getAction(), event.getButton(),
         occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      if (SelectionInputDispatcher.queueSelectionPointer(minecraft, event.getAction(), event.getButton(), occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      if (queueOperationPointDragRelease(minecraft, event.getAction(), event.getButton(), occurredAtNanos)
         || queuePointerRelease(event.getAction(), event.getButton(), occurredAtNanos)) {
         event.setCanceled(true);
         return;
      }
      ClientInputStateMachine.Dispatch capturedRoute = inputOwner.routing.dispatch(ClientInputStateMachine.InputKind.POINTER);
      if (capturedRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         event.setCanceled(true);
         return;
      }
      if (capturedRoute == ClientInputStateMachine.Dispatch.VANILLA
         || capturedRoute == ClientInputStateMachine.Dispatch.BUILDING) return;
      if (event.getAction() == MouseButtonInputSemantics.PRESS) inputOwner.selectionPointer.clear();
      InputWorldCleanup.synchronizeInputState();
      ClientInputStateMachine.Dispatch inputRoute = inputOwner.routing.dispatch(ClientInputStateMachine.InputKind.POINTER);
      boolean buildingSession = inputRoute == ClientInputStateMachine.Dispatch.BUILDING;
      boolean geometrySession = inputRoute == ClientInputStateMachine.Dispatch.GEOMETRY;
      boolean operationSession = inputRoute == ClientInputStateMachine.Dispatch.OPERATION;
      if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) {
         return;
      }
      if (inputRoute == ClientInputStateMachine.Dispatch.BLOCKED) {
         event.setCanceled(true);
         return;
      }
      if (event.getAction() == 1) {
         if (inputOwner.routing.accepts(inputOwner.clickGestureToken)
            && (inputOwner.pointerGesture.kind() != PointerGestureState.Kind.NONE || inputOwner.undoPressCaptured)) {
            InputWorldCleanup.cancelOperationGesture(minecraft);
         }
         inputOwner.clickGestureToken = 0L;
         // A legacy gesture may have blocked capture before its cancellation above.
         if (SelectionInputDispatcher.queueSelectionPointer(minecraft, event.getAction(), event.getButton(), occurredAtNanos)) {
            event.setCanceled(true);
            return;
         }
         inputOwner.clickGestureToken = inputOwner.routing.beginGesture(event.getButton());
      } else if (event.getAction() == 0) {
         if (!inputOwner.routing.finishGesture(event.getButton(), inputOwner.clickGestureToken)) {
            if (!inputOwner.routing.accepts(inputOwner.clickGestureToken)) {
               inputOwner.clickGestureToken = 0L;
            }
            return;
         }
         inputOwner.clickGestureToken = 0L;
      }
      boolean altMouseChord = ModifierCommandDispatcher.physicalAltDown(minecraft, false) || inputOwner.modifier.held();
      OperationInteractionIntent pointerIntent = ClientSessionSystem.instance().operations().pointer().resolve(InteractionContext.capture(Minecraft.getInstance())).orElse(null);
      // Alt is an explicit "new selection" chord. It must win over gizmos,
      // existing-part selection and vanilla placement, except for an explicit
      // Gizmo handle. That direct handle hit starts a transform while Alt
      // continues to create a selection everywhere else.
      if (MouseButtonInputSemantics.startsAltWorkspaceGizmoTransform(
         altMouseChord,
         pointerIntent instanceof OperationInteractionIntent.Gizmo,
         event.getAction(),
         event.getButton()
      )) {
         inputOwner.operationClick.capture(event.getButton());
         event.setCanceled(true);
         return;
      }
      if (inputOwner.modifier.held() && event.getAction() == 1) {
         inputOwner.modifier.consume();
      }
      if (ModifierCommandDispatcher.modifierHeld()
         && event.getAction() == 1
         && (event.getButton() == 0 || event.getButton() == 1)
         && inputOwner.operationDrag == null
         && InputWorldCleanup.workspaceGizmoDrag() == null
         && InputWorldCleanup.workspaceFaceDrag() == null) {
         if (ClientSessionSystem.instance().operations().active()) {
            inputOwner.modifier.consume();
            inputOwner.operationClick.capture(event.getButton());
            event.setCanceled(true);
         }
         return;
      }
      if (event.getButton() == 0
         && (buildingSession || geometrySession || operationSession)
         && (ServerGateway.supports(minecraft, UndoFastPlacePayload.TYPE)
            || ServerGateway.supports(minecraft, GeometryInteractionPayload.TYPE))) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            boolean operationAdjust = operationSession
               && ClientSessionSystem.instance().operations().operationSelectionReady()
               && ModifierCommandDispatcher.operationAdjustModifierHeld();
            boolean operationAlt = operationSession && ModifierCommandDispatcher.modifierHeld();
            OperationInputSemantics.LeftDecision leftDecision = SelectionInputDispatcher.operationLeftDecision(pointerIntent);
            PointerDecision.Left target = SelectionInputDispatcher.leftPressTarget(
               minecraft, event.getAction(), pointerIntent, leftDecision, operationAdjust, operationAlt,
               buildingSession, geometrySession, operationSession
            );
            consumed = switch (target) {
               case NONE -> false;
               case YIELD_TO_VANILLA -> false;
               case WORKSPACE_POINTER -> {
                  inputOwner.operationClick.capture(0);
                  yield true;
               }
               case WORKSPACE_ADJUST_OR_CAPTURE -> {
                  // A confirmed client workspace owns the interaction surface.
                  inputOwner.operationClick.capture(0);
                  yield true;
               }
               case OPERATION_GIZMO -> {
                  SelectionInputDispatcher.beginOperationGizmoDrag(minecraft, 0);
                  inputOwner.operationClick.capture(0);
                  yield true;
               }
               case OPERATION_POINT -> {
                  queueOperationPointDrag(minecraft, 0, occurredAtNanos);
                  inputOwner.operationClick.capture(0);
                  yield true;
               }
               case UNDO -> beginUndoPress();
               case OPERATION_ALT -> {
                  boolean handled = !inputOwner.operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON) && SelectionInputDispatcher.beginOperationGizmoDrag(minecraft, 0);
                  if (handled) inputOwner.modifier.consume();
                  inputOwner.operationClick.capture(0);
                  yield true;
               }
               case OPERATION_ADJUST -> {
                  boolean handled = SelectionInputDispatcher.beginOperationGizmoDrag(minecraft, 0);
                  if (!handled && ClientSessionSystem.instance().operations().operationPrism()) {
                     handled = SelectionInputDispatcher.sendOperationPointSelection(minecraft)
                        || SelectionInputDispatcher.beginOperationFaceAdjustment(minecraft, -1, 0);
                  }
                  if (!inputOwner.operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON) && handled) inputOwner.modifier.consume();
                  inputOwner.operationClick.capture(0);
                  yield true;
               }
               case CUBOID_POINT_UNDO -> {
                  SelectionInputDispatcher.queueCuboidPointUndo(minecraft, System.nanoTime());
                  inputOwner.operationClick.capture(0);
                  yield true;
               }
               case CUBOID_FACE -> {
                  SelectionInputDispatcher.beginOperationFaceAdjustment(minecraft, -1, 0);
                  inputOwner.operationClick.capture(0);
                  yield true;
               }
               case OPERATION_CAPTURE -> {
                  inputOwner.operationClick.capture(0);
                  yield true;
               }
               case GEOMETRY_CAPTURE, GEOMETRY_GIZMO_CAPTURE -> true;
               case GEOMETRY_INTERACTION -> {
                  yield operationSession ? captureOperationPress() : beginFallbackUndoPress(minecraft);
               }
            };
         } else if (event.getAction() == MouseButtonInputSemantics.RELEASE) {
            consumed = MouseReleaseDispatcher.finish(minecraft, inputOwner, event.getAction(), event.getButton(), occurredAtNanos);
         }
         if (consumed) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 1 && geometrySession) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            PointerDecision.Right target = SelectionInputDispatcher.rightPressTarget(
               minecraft, event.getAction(), pointerIntent, true, false
            );
            if (target == PointerDecision.Right.YIELD_TO_VANILLA) return;
            consumed = target == PointerDecision.Right.GEOMETRY_CAPTURE;
         } else if (event.getAction() == 0
            && inputOwner.ownsGeometryPointerButton(1)) {
            inputOwner.releaseGeometryPointerButton(1);
            consumed = true;
         } else if (event.getAction() == 0
            && inputOwner.geometryGizmoDrag != null
            && inputOwner.geometryGizmoDrag.mouseButton() == 1) {
            consumed = MouseReleaseDispatcher.finishGeometryRightRelease(minecraft, inputOwner);
         }
         if (consumed) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 1 && operationSession) {
         boolean consumed = false;
         if (event.getAction() == 1) {
            PointerDecision.Right target = SelectionInputDispatcher.rightPressTarget(
               minecraft, event.getAction(), pointerIntent, false, true
            );
            if (target == PointerDecision.Right.YIELD_TO_VANILLA) return;
            consumed = switch (target) {
               case NONE, YIELD_TO_VANILLA, GEOMETRY_CAPTURE, GEOMETRY_INTERACTION -> false;
               case WORKSPACE_POINTER -> {
                  inputOwner.operationClick.capture(1);
                  yield true;
               }
               case WORKSPACE_ADJUST_OR_CAPTURE -> {
                  inputOwner.operationClick.capture(1);
                  yield true;
               }
               case CONFIRMED_GIZMO_CAPTURE -> {
                  SelectionInputDispatcher.beginOperationGizmoDrag(minecraft, 1);
                  inputOwner.operationClick.capture(1);
                  yield true;
               }
               case OPERATION_ALT -> {
                  if (SelectionInputDispatcher.beginOperationGizmoDrag(minecraft, 1)) inputOwner.modifier.consume();
                  inputOwner.operationClick.capture(1);
                  yield true;
               }
               case OPERATION_ADJUST -> {
                  if (SelectionInputDispatcher.beginOperationGizmoDrag(minecraft, 1)
                     || SelectionInputDispatcher.sendOperationPointSelection(minecraft)
                     || SelectionInputDispatcher.beginOperationFaceAdjustment(minecraft, 1, 1)) {
                     inputOwner.modifier.consume();
                  }
                  inputOwner.operationClick.capture(1);
                  yield true;
               }
               case OPERATION_POINT -> {
                  queueOperationPointDrag(minecraft, 1, occurredAtNanos);
                  inputOwner.operationClick.capture(1);
                  yield true;
               }
               case OPERATION_EDGE -> {
                  SelectionInputDispatcher.sendOperationEdgeInsertion(minecraft);
                  inputOwner.operationClick.capture(1);
                  yield true;
               }
               case OPERATION_NEXT_POINT -> {
                  SelectionInputDispatcher.sendNextOperationPrismPoint(minecraft);
                  inputOwner.operationClick.capture(1);
                  yield true;
               }
               case CUBOID_NEXT_POINT -> {
                  if (inputOwner.acceptCuboidPointPress(MouseButtonInputSemantics.RIGHT_BUTTON,
                     ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint(), occurredAtNanos)) {
                     SelectionInputDispatcher.queueRemoteSelectionPoint(SelectionInputDispatcher.nextCuboidPointRole());
                  }
                  inputOwner.operationClick.capture(1);
                  yield true;
               }
               case CUBOID_FACE -> {
                  boolean handled = SelectionInputDispatcher.beginOperationFaceAdjustment(minecraft, 1, 1);
                  inputOwner.operationClick.capture(1);
                  yield handled;
               }
               case CLOSE_PATH -> PathCloseInputDispatcher.press(minecraft, inputOwner, false, occurredAtNanos);
            };
         } else if (event.getAction() == MouseButtonInputSemantics.RELEASE) {
            consumed = MouseReleaseDispatcher.finish(minecraft, inputOwner, event.getAction(), event.getButton(), occurredAtNanos);
         }
         if (consumed) {
            event.setCanceled(true);
         }
      } else if (event.getButton() == 2
         && event.getAction() == 1
         && operationSession
         && ServerGateway.supports(minecraft, OperationPointPayload.TYPE)) {
         BlockPos point = ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint();
         if (point != null) {
            SelectionInputDispatcher.queueRemoteSelectionPoint(OperationPointPayload.Role.EXTRA);
            event.setCanceled(true);
         }
      }
   }

   static boolean queuePointerRelease(int action, int button, long occurredAtNanos) {
      if (action != MouseButtonInputSemantics.RELEASE) return false;
      var session = ClientSessionSystem.instance().inputSession();
      var target = MouseReleaseDispatcher.target(session, action, button);
      if (target == MouseDragReleaseSemantics.Target.NONE
         || !session.routing.accepts(button, session.clickGestureToken)) {
         return false;
      }
      session.postPointerRelease(new PointerReleaseSnapshot(button, occurredAtNanos,
         session.clickGestureToken, session.pointerGesture.activeToken(), target));
      return true;
   }

   static void queueOperationPointDrag(Minecraft minecraft, int button, long occurredAtNanos) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      OperationPointInputController.capturePress(minecraft, inputOwner, button, occurredAtNanos)
         .ifPresent(inputOwner::captureOperationPointDrag);
   }

   static boolean queueOperationPointDragRelease(Minecraft minecraft, int action, int button, long occurredAtNanos) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (action != MouseButtonInputSemantics.RELEASE) return false;
      var release = inputOwner.operationPointPointer.release(button, occurredAtNanos,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F));
      if (release == null) return false;
      inputOwner.postOperationPointDrag(release);
      return true;
   }

   static void onVanillaMouseButton(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      if (event.getAction() == MouseButtonInputSemantics.PRESS && minecraft.screen == null
         && minecraft.options.keyUse.matchesMouse(event.getButton())) {
         ClientForcedPlacement.input(minecraft.player, event.getAction());
      }
   }

   static boolean beginUndoPress() {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (!inputOwner.undoPressCaptured) {
         inputOwner.undoPress.press(System.nanoTime());
         inputOwner.undoPressCaptured = true;
      }
      return true;
   }

   static boolean captureOperationPress() {
      ClientSessionSystem.instance().inputSession().operationClick.capture(MouseButtonInputSemantics.LEFT_BUTTON);
      return true;
   }

   static boolean beginFallbackUndoPress(Minecraft minecraft) {
      return ServerGateway.supports(minecraft, UndoFastPlacePayload.TYPE) && beginUndoPress();
   }

   static boolean leftMousePressAlreadyHandled() {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      return inputOwner.operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON)
         || inputOwner.ownsGeometryPointerButton(0)
         || inputOwner.undoPressCaptured
         || inputOwner.operationDrag != null && inputOwner.operationDrag.mouseButton() == 0
         || inputOwner.operationPointDrag != null && inputOwner.operationPointDrag.mouseButton() == 0
         || InputWorldCleanup.workspaceGizmoDrag() != null && InputWorldCleanup.workspaceGizmoDrag().mouseButton() == 0
         || InputWorldCleanup.workspaceFaceDrag() != null && InputWorldCleanup.workspaceFaceDrag().mouseButton() == 0
         || inputOwner.geometryGizmoDrag != null && inputOwner.geometryGizmoDrag.mouseButton() == 0;
   }
}
