package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.OperationContext;
import io.github.fastformer.client.interaction.selectiongesture.SelectionGesturePress;
import io.github.fastformer.client.interaction.selectiongesture.SelectionFaceHit;
import io.github.fastformer.client.interaction.InteractionPressBinding;
import io.github.fastformer.client.network.outbound.ServerGateway;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.mode.input.pointer.PointerDecision;
import io.github.fastformer.client.mode.input.pointer.PointerInput;
import io.github.fastformer.client.mode.input.pointer.PointerView;
import io.github.fastformer.client.mode.ClientModes;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.input.OperationInputSemantics;
import io.github.fastformer.client.operation.input.RemoteSelectionPointRequest;
import io.github.fastformer.client.operation.input.SelectionPointPress;
import io.github.fastformer.client.operation.input.SelectionPointerPress;
import io.github.fastformer.client.operation.pointer.OperationPointerKind;
import io.github.fastformer.client.operation.pointer.OperationPointerTarget;
import io.github.fastformer.protocol.c2s.geometry.GeometryInteractionPayload;
import io.github.fastformer.protocol.c2s.operation.OperationInsertPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointDragPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import io.github.fastformer.protocol.c2s.placement.UndoFastPlacePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.selectiongesture.SelectionDragCapture;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointerCapture;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import net.minecraft.client.Minecraft;
import io.github.fastformer.client.interaction.SmartSelectionEditView;
import io.github.fastformer.client.operation.selection.SelectionToolPreference;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import java.util.function.Consumer;

final class SelectionInputDispatcher {
   private SelectionInputDispatcher() { }

   static void dispatch(Minecraft minecraft, ClientInputSession session, SelectionPointerEvent event) {
      switch (event) {
         case SelectionPointerEvent.Press press -> {
            SelectionDragCapture capture = null;
            long routingToken = 0L;
            if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.OPERATION
               && !session.selectionGestures().active()) {
               FastPlaceClientInput.dispatchSelectionGesture(session, ClientSessionSystem.instance().operations(), press.snapshot());
               capture = session.selectionGestures().capture();
               if (capture != null) {
                  routingToken = session.routing.beginGesture(press.snapshot().button());
                  session.clickGestureToken = routingToken;
                  session.operationClick.capture(press.snapshot().button());
                  if (press.alt()) session.modifier.consume();
               }
            }
            session.selectionPointer.dispatched(press.identity(), capture, routingToken);
         }
         case SelectionPointerEvent.Release release ->
            settleSelectionPointer(session, session.selectionPointer.take(release.identity()), false);
         case SelectionPointerEvent.Cancel cancel ->
            settleSelectionPointer(session, session.selectionPointer.take(cancel.identity()), true);
         case SelectionPointerEvent.CreatePress press -> {
            handleSelectionDraftPress(session, press.snapshot());
            session.selectionPointer.dispatched(press.identity(), null, 0L);
         }
         case SelectionPointerEvent.PointPress press -> {
            var snapshot = press.snapshot();
            if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.OPERATION
               && snapshot.matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().workspace())
               && !session.selectionGestures().active() && snapshot.point() != null) {
               var decision = ClientSessionSystem.instance().operations().transforms().adjustAabbPoint(snapshot.partId(), snapshot.button(), snapshot.point());
               ClientInteractionFeedback.showAabbAdjustFailure(minecraft, decision);
            }
            session.selectionPointer.dispatched(press.identity(), null, 0L);
         }
      }
   }

   private static void handleSelectionDraftPress(ClientInputSession session, SelectionDraftPress press) {
      if (session.routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION) != ClientInputStateMachine.Dispatch.OPERATION
         || !press.matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode())
         || ClientSessionSystem.instance().operations().workspaceSubmissionPending() || ClientSessionSystem.instance().operations().workspace().locked()
         || session.selectionGestures().active()) return;
      if (press.alt()) {
         ClientSessionSystem.instance().operations().setAltMode(true);
         session.modifier.consume();
         if (press.point() != null || press.button() == 0 && press.mode() == OperationSelectionMode.CUBOID)
            ClientSessionSystem.instance().operations().selectionEdits().handleAltCreateClick(press.button(), press.point());
      } else {
         ClientSessionSystem.instance().operations().selectionEdits().handleCreateClick(press.button(), press.point());
      }
   }

   private static void settleSelectionPointer(ClientInputSession session, SelectionPointerCapture.Dispatch dispatched, boolean cancelled) {
      if (dispatched == null || dispatched.drag() == null) return;
      SelectionDragCapture capture = dispatched.drag();
      var gestures = session.selectionGestures();
      if (cancelled) gestures.cancel(capture);
      else gestures.finish(capture);
      if (session.clickGestureToken == dispatched.routingToken()) {
         session.routing.finishGesture(capture.mouseButton(), dispatched.routingToken());
         session.clickGestureToken = 0L;
         session.operationClick.clear();
      }
   }

   static boolean captureInitialSelection(int button, BlockPos point, boolean alt, boolean control, long occurredAtNanos) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      if (button < 0 || button > 2
         || inputOwner.routing.state() != ClientInputStateMachine.State.IDLE
         || ClientSessionSystem.instance().operations().active() || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive()) return false;
      if (button == MouseButtonInputSemantics.LEFT_BUTTON
         && SelectionToolPreference.get()
            == OperationSelectionMode.SMART) return true;
      if (point == null) return false;
      ClientSessionSystem.instance().operations().selectionEdits().enterLocalSelectionSession();
      inputOwner.routing.observe(ClientInputStateMachine.State.SELECTING);
      inputOwner.captureSelectionPress(new SelectionDraftPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
         ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode(), button, point, alt, control, occurredAtNanos));
      return true;
   }

   static boolean captureSelectionEntry(BlockPos point, boolean control, long occurredAtNanos) {
      return captureInitialSelection(MouseButtonInputSemantics.RIGHT_BUTTON, point, false, control, occurredAtNanos);
   }

   static boolean queueSelectionPointer(Minecraft minecraft, int action, int button, long occurredAtNanos) {
      var session = ClientSessionSystem.instance().inputSession();
      if (action == MouseButtonInputSemantics.RELEASE) {
         var release = session.selectionPointer.release(button, occurredAtNanos);
         if (release == null) return false;
         session.postSelectionPointer(release);
         return true;
      }
      boolean ownSelectionDrag = session.selectionPointer.owns(session.selectionGestures().capture());
      if (action != MouseButtonInputSemantics.PRESS || InputWorldCleanup.pointerGestureInFlight() && !ownSelectionDrag
         || session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) != ClientInputStateMachine.Dispatch.OPERATION) {
         return false;
      }
      var target = ClientSessionSystem.instance().operations().pointer().resolve(InteractionContext.capture(Minecraft.getInstance())).orElse(null);
      boolean alt = ModifierCommandDispatcher.physicalAltDown(minecraft, false);
      if (ClientSessionSystem.instance().operations().selectionEdits().smartTool() && !(target instanceof OperationInteractionIntent.Gizmo)
         && (ClientSessionSystem.instance().operations().selectionEdits().smartEditing() || button == MouseButtonInputSemantics.RIGHT_BUTTON)) {
         BlockPos point;
         if (button == MouseButtonInputSemantics.LEFT_BUTTON) {
            var member = SmartSelectionEditView.target(minecraft, alt);
            point = member == null ? null : member.pick().position();
         } else if (alt && button == MouseButtonInputSemantics.RIGHT_BUTTON) {
            var member = SmartSelectionEditView.target(minecraft, true);
            point = SmartSelectionEditView.additionTarget(minecraft, member);
         } else {
            BlockHitResult hit = SmartSelectionEditView.worldTarget(minecraft);
            point = hit == null ? null : hit.getBlockPos();
         }
         // A miss is consumed without creating a point command or breaking a world block.
         if (point == null) return true;
         session.captureSelectionPress(new SelectionDraftPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
            ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode(), button, point,
            alt, ModifierCommandDispatcher.physicalCtrlDown(minecraft, false), occurredAtNanos));
         return true;
      }
      boolean altGizmo = MouseButtonInputSemantics.startsAltWorkspaceGizmoTransform(
         alt, target instanceof OperationInteractionIntent.Gizmo, action, button);
      if (button == MouseButtonInputSemantics.MIDDLE_BUTTON && ClientSessionSystem.instance().operations().active()
         && !ClientSessionSystem.instance().operations().selectionEdits().canStartSelectionDraft()) {
         queueSelectionPoint(minecraft, button, occurredAtNanos);
         return true;
      }
      if (button == MouseButtonInputSemantics.MIDDLE_BUTTON && ClientSessionSystem.instance().operations().selectionEdits().canStartSelectionDraft()
         && !(target instanceof OperationInteractionIntent.CreateSelection)) {
         BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
         if (hit == null) return false;
         target = new OperationInteractionIntent.CreateSelection(hit.getBlockPos());
      }
      if (!altGizmo && queueSelectionDraft(minecraft, target, button, alt, occurredAtNanos)) return true;
      if (!ClientSessionSystem.instance().operations().active()) return false;
      if (alt && !altGizmo) return false;
      SelectionPressRoute route = altGizmo ? SelectionPressRoute.OBJECT : selectionPressRoute(minecraft, target, button);
      if (route == SelectionPressRoute.NONE) return false;
      if (route == SelectionPressRoute.POINT) {
         queueSelectionPoint(minecraft, button, occurredAtNanos);
         return true;
      }
      var press = SelectionPointerPress.capture(target, button, ModifierCommandDispatcher.physicalCtrlDown(minecraft, false),
         button == MouseButtonInputSemantics.RIGHT_BUTTON ? 1 : -1,
         ClientSessionSystem.instance().operations().interactionScene(), ClientSessionSystem.instance().operations().workspace(), occurredAtNanos);
      if (press.isEmpty()) return false;
      session.captureSelectionPress(press.orElseThrow(), alt);
      return true;
   }

   static SelectionPressRoute selectionPressRoute(Minecraft minecraft, OperationInteractionIntent target, int button) {
      return switch (button) {
         case MouseButtonInputSemantics.LEFT_BUTTON -> {
            if (!ServerGateway.supports(minecraft, UndoFastPlacePayload.TYPE)
               && !ServerGateway.supports(minecraft, GeometryInteractionPayload.TYPE)) {
               yield SelectionPressRoute.NONE;
            }
            yield switch (leftPressTarget(minecraft, MouseButtonInputSemantics.PRESS, target, operationLeftDecision(target),
               false, false, false, false, true)) {
               case WORKSPACE_POINTER -> SelectionPressRoute.OBJECT;
               case WORKSPACE_ADJUST_OR_CAPTURE -> SelectionPressRoute.POINT;
               default -> SelectionPressRoute.NONE;
            };
         }
         case MouseButtonInputSemantics.RIGHT_BUTTON -> switch (
            rightPressTarget(minecraft, MouseButtonInputSemantics.PRESS, target, false, true)) {
            case WORKSPACE_POINTER -> SelectionPressRoute.OBJECT;
            case WORKSPACE_ADJUST_OR_CAPTURE -> SelectionPressRoute.POINT;
            default -> SelectionPressRoute.NONE;
         };
         case MouseButtonInputSemantics.MIDDLE_BUTTON -> SelectionPressRoute.POINT;
         default -> SelectionPressRoute.NONE;
      };
   }

   static void queueSelectionPoint(Minecraft minecraft, int button, long occurredAtNanos) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      var workspace = ClientSessionSystem.instance().operations().workspace();
      int partId = button == MouseButtonInputSemantics.MIDDLE_BUTTON
         ? workspace.selections().topPart().map(ClientSelectionPart::id).orElse(0)
         : workspace.activeId();
      BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
      if (ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode() == OperationSelectionMode.CUBOID
         && !inputOwner.acceptCuboidPointPress(button, hit == null ? null : hit.getBlockPos(), occurredAtNanos)) return;
      inputOwner.captureSelectionPress(new SelectionPointPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
         partId, workspace.interactionId(partId), button, ModifierCommandDispatcher.physicalCtrlDown(minecraft, false),
         hit == null ? null : hit.getBlockPos(), occurredAtNanos));
   }

   static boolean canCreateSelection() {
      return ClientSessionSystem.instance().inputSession().canCreateSelection();
   }

   static boolean queueSelectionDraft(
      Minecraft minecraft, OperationInteractionIntent target, int button, boolean alt, long occurredAtNanos
   ) {
      var session = ClientSessionSystem.instance().inputSession();
      if (button < 0 || button > 2) return false;
      if (button == MouseButtonInputSemantics.LEFT_BUTTON
         && ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode() == OperationSelectionMode.CUBOID
         && !(target instanceof OperationInteractionIntent.Gizmo)
         && (ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive() || target == null || target instanceof OperationInteractionIntent.CreateSelection)) {
         queueCuboidPointUndo(minecraft, alt, occurredAtNanos);
         return true;
      }
      // Keep the initial points with their server owner until the selection is complete.
      if (!alt && ClientSessionSystem.instance().operations().selectionEdits().remoteSelectionPointing()) {
         return queueRemoteSelectionContinuation(target, button, occurredAtNanos);
      }
      BlockPos point;
      if (MouseButtonInputSemantics.startsAltCreateSelection(alt,
         session.routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION) == ClientInputStateMachine.Dispatch.OPERATION,
         MouseButtonInputSemantics.PRESS, button)) {
         BlockHitResult hit = longRangeSelectionBlockHit(minecraft);
         point = hit == null ? null : hit.getBlockPos();
      } else if (!alt && (ClientSessionSystem.instance().operations().active() || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive() || ClientSessionSystem.instance().operations().selectionSessionActive())
         && acceptsSelectionDraftTarget(target, button)
         && target instanceof OperationInteractionIntent.CreateSelection create) {
         point = create.point();
      } else {
         return false;
      }
      session.captureSelectionPress(new SelectionDraftPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
         ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode(), button, point, alt, ModifierCommandDispatcher.physicalCtrlDown(minecraft, false), occurredAtNanos));
      return true;
   }

   static boolean acceptsSelectionDraftTarget(OperationInteractionIntent target, int button) {
      return target instanceof OperationInteractionIntent.CreateSelection
         && (button != MouseButtonInputSemantics.MIDDLE_BUTTON || ClientSessionSystem.instance().operations().selectionEdits().canStartSelectionDraft());
   }

   static void queueCuboidPointUndo(Minecraft minecraft, long occurredAtNanos) {
      queueCuboidPointUndo(minecraft, ModifierCommandDispatcher.modifierHeld(), occurredAtNanos);
   }

   static void queueCuboidPointUndo(Minecraft minecraft, boolean alt, long occurredAtNanos) {
      ClientSessionSystem.instance().inputSession().captureSelectionPress(new SelectionDraftPress(ClientSessionSystem.instance().operations().interactionScene().owner(),
         OperationSelectionMode.CUBOID, MouseButtonInputSemantics.LEFT_BUTTON,
         null, alt, ModifierCommandDispatcher.physicalCtrlDown(minecraft, false), occurredAtNanos));
   }

   static boolean queueRemoteSelectionContinuation(OperationInteractionIntent target, int button, long occurredAtNanos) {
      if (!ClientSessionSystem.instance().operations().selectionEdits().remoteSelectionPointing() || !ClientSessionSystem.instance().operations().operationCuboid()
         || !(target instanceof OperationInteractionIntent.CreateSelection create)) return false;
      OperationPointPayload.Role role = switch (button) {
         case MouseButtonInputSemantics.RIGHT_BUTTON -> nextCuboidPointRole();
         case MouseButtonInputSemantics.MIDDLE_BUTTON -> OperationPointPayload.Role.EXTRA;
         default -> null;
      };
      if (role == null) return false;
      if (!ClientSessionSystem.instance().inputSession().acceptCuboidPointPress(button, create.point(), occurredAtNanos)) return true;
      queueRemoteSelectionPoint(role);
      return true;
   }

   static OperationPointPayload.Role nextCuboidPointRole() {
      return ClientSessionSystem.instance().operations().remotePointer().operationNeedsFirst() ? OperationPointPayload.Role.FIRST : OperationPointPayload.Role.SECOND;
   }

   static void queueRemoteSelectionPoint(OperationPointPayload.Role role) {
      ClientSessionSystem.instance().inputSession().postRemoteSelectionPoint(RemoteSelectionPointDispatcher.capture(role));
   }

   static void handleRemoteSelectionPoint(RemoteSelectionPointRequest request,
      Consumer<OperationPointPayload> send) {
      RemoteSelectionPointDispatcher.dispatch(request, ClientSessionSystem.instance().inputSession().routing, send);
   }

   static PointerDecision.Left leftPressTarget(
      Minecraft minecraft,
      int action,
      OperationInteractionIntent pointerIntent,
      OperationInputSemantics.LeftDecision leftDecision,
      boolean operationAdjust,
      boolean operationAlt,
      boolean buildingSession,
      boolean geometrySession,
      boolean operationSession
   ) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      OperationPointerTarget pointerTarget = ClientSessionSystem.instance().operations().remotePointer().operationPointerTarget();
      boolean operationPointTarget = ClientSessionSystem.instance().operations().operationPrism()
         && inputOwner.operationDrag == null
         && inputOwner.operationPointDrag == null
         && ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex() >= 0
         && ClientSessionSystem.instance().operations().remotePointer().operationPointCenter(ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex()) != null
         && ServerGateway.supports(minecraft, OperationPointDragPayload.TYPE);
      boolean operationUndo = leftDecision.action() == OperationInputSemantics.LeftAction.SELECTION_UNDO
         || leftDecision.action() == OperationInputSemantics.LeftAction.ADJUSTMENT_UNDO;
      if (!buildingSession && !geometrySession && !operationSession) return PointerDecision.Left.NONE;
      var decision = ClientModes.registry().onPointer(
         pointerPhase(buildingSession, geometrySession, operationSession),
         new PointerInput(action, MouseButtonInputSemantics.LEFT_BUTTON), new PointerView.Left(
         leftDecision.yieldToVanilla(),
         workspacePointerTarget(pointerIntent),
         ClientSessionSystem.instance().operations().active(),
         leftDecision.action() == OperationInputSemantics.LeftAction.GIZMO_DRAG,
         operationPointTarget,
         operationUndo,
         operationAlt,
         operationAdjust,
         ClientSessionSystem.instance().operations().operationCuboid()
            && !InteractionContext.vanillaOwnsPointer(minecraft)
            && pointerTarget.kind() == OperationPointerKind.WORLD,
         ClientSessionSystem.instance().operations().operationCuboid()
            && !InteractionContext.vanillaOwnsPointer(minecraft)
            && pointerTarget.kind() == OperationPointerKind.FACE,
         inputOwner.operationClick.owns(MouseButtonInputSemantics.LEFT_BUTTON),
         inputOwner.operationDrag != null && inputOwner.operationDrag.mouseButton() == MouseButtonInputSemantics.LEFT_BUTTON,
         inputOwner.ownsGeometryPointerButton(MouseButtonInputSemantics.LEFT_BUTTON),
         inputOwner.geometryGizmoDrag != null,
         geometrySession && ClientSessionSystem.instance().geometryPointer().gizmoHit() != null,
         geometrySession,
         ServerGateway.supports(minecraft, UndoFastPlacePayload.TYPE)
      ));
      return decision instanceof PointerDecision.Left left ? left : PointerDecision.Left.NONE;
   }

   static PointerDecision.Right rightPressTarget(
      Minecraft minecraft,
      int action,
      OperationInteractionIntent pointerIntent,
      boolean geometrySession,
      boolean operationSession
   ) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      OperationPointerTarget pointerTarget = ClientSessionSystem.instance().operations().remotePointer().operationPointerTarget();
      boolean vanillaOwnsPointer = InteractionContext.vanillaOwnsPointer(minecraft);
      boolean operationAdjust = operationSession
         && ClientSessionSystem.instance().operations().operationSelectionReady()
         && ModifierCommandDispatcher.operationAdjustModifierHeld();
      boolean operationPointTarget = ClientSessionSystem.instance().operations().operationPrism()
         && inputOwner.operationDrag == null
         && inputOwner.operationPointDrag == null
         && ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex() >= 0
         && ClientSessionSystem.instance().operations().remotePointer().operationPointCenter(ClientSessionSystem.instance().operations().remotePointer().operationPointUnderCrosshairIndex()) != null
         && ServerGateway.supports(minecraft, OperationPointDragPayload.TYPE);
      if (!geometrySession && !operationSession) return PointerDecision.Right.NONE;
      var decision = ClientModes.registry().onPointer(
         pointerPhase(false, geometrySession, operationSession),
         new PointerInput(action, MouseButtonInputSemantics.RIGHT_BUTTON), new PointerView.Right(
         vanillaOwnsPointer,
         inputOwner.ownsGeometryPointerButton(MouseButtonInputSemantics.RIGHT_BUTTON),
         workspacePointerTarget(pointerIntent),
         ClientSessionSystem.instance().operations().active(),
         ClientSessionSystem.instance().operations().operationSelectionConfirmed(),
         operationAdjust,
         operationSession && ModifierCommandDispatcher.modifierHeld(),
         operationPointTarget,
         !vanillaOwnsPointer
            && ClientSessionSystem.instance().operations().operationPrism()
            && ClientSessionSystem.instance().operations().remotePointer().operationPrismEdgeInsertion() != null
            && ServerGateway.supports(minecraft, OperationInsertPointPayload.TYPE),
         !vanillaOwnsPointer
            && ClientSessionSystem.instance().operations().operationPrism()
            && ClientSessionSystem.instance().operations().remotePointer().operationCandidatePoint() != null
            && ServerGateway.supports(minecraft, OperationPointPayload.TYPE),
         ClientSessionSystem.instance().operations().operationCuboid() && pointerTarget.kind() == OperationPointerKind.WORLD,
         ClientSessionSystem.instance().operations().operationCuboid() && pointerTarget.kind() == OperationPointerKind.FACE,
         !ClientSessionSystem.instance().operations().operationPrism() && ClientSessionSystem.instance().operations().remotePointer().operationPrismBaseOpen()
      ));
      return decision instanceof PointerDecision.Right right ? right : PointerDecision.Right.NONE;
   }

   static ClientInputStateMachine.State pointerPhase(boolean building, boolean geometry, boolean selection) {
      // These routes were captured before physical gesture setup and are still authoritative.
      return geometry ? ClientInputStateMachine.State.GEOMETRY
         : selection ? ClientInputStateMachine.State.SELECTING
         : building ? ClientInputStateMachine.State.BUILDING : ClientInputStateMachine.State.IDLE;
   }

   static boolean workspacePointerTarget(OperationInteractionIntent pointerIntent) {
      return pointerIntent instanceof OperationInteractionIntent.Gizmo
         || pointerIntent instanceof OperationInteractionIntent.Face
         || pointerIntent instanceof OperationInteractionIntent.Part part && part.partId() > 0;
   }

   static boolean beginOperationGizmoDrag(Minecraft minecraft, int mouseButton) {
      var inputOwner = ClientSessionSystem.instance().inputSession();
      OperationInteractionIntent.Gizmo workspaceTarget = ClientSessionSystem.instance().operations().pointer().gizmo(InteractionContext.capture(Minecraft.getInstance()));
      if (workspaceTarget != null) {
         var press = SelectionPointerPress.capture(workspaceTarget, mouseButton, ModifierCommandDispatcher.physicalCtrlDown(minecraft, false), 0,
            ClientSessionSystem.instance().operations().interactionScene(), ClientSessionSystem.instance().operations().workspace());
         return press.isPresent() && inputOwner.selectionGestures().beginGizmo(selectionGizmoPress(workspaceTarget, press.get().button(), press.get().control()));
      }
      return OperationDragController.beginGizmo(minecraft, inputOwner, mouseButton);
   }

   static boolean sendOperationEdgeInsertion(Minecraft minecraft) {
      return OperationPointCommandDispatcher.insertEdge(minecraft);
   }

   static boolean sendNextOperationPrismPoint(Minecraft minecraft) {
      return OperationPointCommandDispatcher.queueNext(ClientSessionSystem.instance().inputSession(), minecraft);
   }

   static boolean sendOperationPointSelection(Minecraft minecraft) {
      return OperationPointCommandDispatcher.select(minecraft);
   }

   static boolean beginOperationFaceAdjustment(Minecraft minecraft, int steps, int mouseButton) {
      return OperationDragController.beginFace(minecraft, ClientSessionSystem.instance().inputSession(), mouseButton, steps);
   }

   static OperationInteractionIntent pointerIntent() {
      return ClientSessionSystem.instance().operations().pointer().resolve(InteractionContext.capture(Minecraft.getInstance())).orElse(null);
   }

   static OperationInputSemantics.LeftDecision operationLeftDecision(
      OperationInteractionIntent pointerIntent
   ) {
      boolean operationActive = ClientSessionSystem.instance().operationActive();
      boolean operationAdjust = operationActive
         && ClientSessionSystem.instance().operations().operationSelectionReady()
         && ModifierCommandDispatcher.operationAdjustModifierHeld();
      boolean operationAlt = operationActive && ModifierCommandDispatcher.modifierHeld();
      boolean gizmoHit = pointerIntent instanceof OperationInteractionIntent.Gizmo
         || !ClientSessionSystem.instance().operations().active() && OperationDragController.gizmoHit(Minecraft.getInstance()) != null;
      boolean interactionTargetHit = gizmoHit
         || pointerIntent instanceof OperationInteractionIntent.Face
         || pointerIntent instanceof OperationInteractionIntent.Part;
      return OperationInputSemantics.decideLeftPress(new OperationInputSemantics.LeftPressSnapshot(
         ClientSessionSystem.instance().operations().operationPrism()
            ? OperationSelectionMode.PRISM
            : OperationSelectionMode.CUBOID,
         operationActive && (ClientSessionSystem.instance().operations().operationSelectionReady() || ClientSessionSystem.instance().operations().active()),
         operationActive && (ClientSessionSystem.instance().operations().operationAdjustmentStarted() || ClientSessionSystem.instance().operations().active()),
         operationActive && gizmoHit,
         InteractionContext.vanillaOwnsPointer(Minecraft.getInstance()),
         operationAdjust || operationAlt,
         interactionTargetHit,
         ClientSessionSystem.instance().operations().active()
      ));
   }

   static BlockHitResult longRangeSelectionBlockHit(Minecraft minecraft) {
      if (minecraft.level == null || minecraft.player == null) return null;
      var hit = ClientSessionSystem.instance().inputSession().pointer().selection(minecraft.player, ModifierCommandDispatcher.physicalAltDown(minecraft, false)).hit();
      return hit.getType() == HitResult.Type.BLOCK ? hit : null;
   }

   static void dispatchSelectionGesture(ClientInputSession input, OperationContext operations, SelectionPointerPress press) {
      if (!press.matches(operations.interactionScene(), operations.workspace())) return;
      var command = switch (press.action()) {
         case InteractionPressBinding.DragGizmo action -> selectionGizmoPress(action.target(), press.button(), press.control());
         case InteractionPressBinding.SelectPart action -> new SelectionGesturePress.Part(action.partId(), press.control());
         case InteractionPressBinding.SelectOrDragFace action -> {
            var target = action.target(); var hit = target.hit();
            yield new SelectionGesturePress.Face(target.partId(),
               hit == null ? null : new SelectionFaceHit(hit.point(), hit.normal(), hit.distance(), hit.axis()),
               target.adjustable(), press.button(), press.shortPressSteps(), press.pressedAtNanos());
         }
      };
      input.selectionGestures().press(command);
   }

   static SelectionGesturePress.Gizmo selectionGizmoPress(OperationInteractionIntent.Gizmo target, int button, boolean control) {
      return new SelectionGesturePress.Gizmo(target.partId(), target.common(), target.gizmo(), target.hit(), button, control);
   }
   enum SelectionPressRoute { NONE, POINT, OBJECT }
}
