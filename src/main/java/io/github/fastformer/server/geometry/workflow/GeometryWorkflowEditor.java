package io.github.fastformer.server.geometry.workflow;

import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflowView;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflow;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflows;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.common.geometry.interaction.PointerGesture;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.session.GeometrySession;
import io.github.fastformer.common.geometry.session.GeometryShapeState;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.raster.GeometryBuildResult;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.GeometryStage;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionTarget;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Mutable draft commands stay on the server and delegate pure queries to shared planning. */
public abstract class GeometryWorkflowEditor<S extends GeometryShapeState> {
   private final Class<S> stateType;
   private final GeometryWorkflow workflow;

   protected GeometryWorkflowEditor(Class<S> stateType, GeometryMode mode) {
      this.stateType = stateType;
      this.workflow = GeometryWorkflows.get(mode);
   }

   public final GeometryMode mode() { return workflow.mode(); }

   private S state(GeometrySession session) {
      if (!stateType.isInstance(session.shape()))
         throw new IllegalArgumentException("Workflow " + mode() + " cannot edit " + session.mode());
      return stateType.cast(session.shape());
   }

   public final void onOpen(GeometrySession session, GeometryActionContext context) { onOpen(state(session), context); }
   protected void onOpen(S state, GeometryActionContext context) { }

   /** Selection clearing is local to the workflow and does not require a hit target. */
   public final boolean clearSelection(GeometrySession session, PointerGesture gesture) {
      return clearSelection(state(session), gesture);
   }
   protected boolean clearSelection(S state, PointerGesture gesture) { return false; }

   public final void onAddPoint(GeometrySession session, GeometryActionContext context, BlockPos point) {
      onAddPoint(state(session), context, point);
   }
   public final void onAddPoint(GeometrySession session, GeometryActionContext context, GeometryHit hit) {
      onAddPoint(state(session), context, hit);
   }
   protected void onAddPoint(S state, GeometryActionContext context, BlockPos point) { state.addPoint(point); }
   protected void onAddPoint(S state, GeometryActionContext context, GeometryHit hit) { onAddPoint(state, context, hit.point()); }

   public final boolean onScroll(GeometrySession session, GeometryActionContext context, int steps) {
      return onScroll(state(session), context, steps);
   }
   protected boolean onScroll(S state, GeometryActionContext context, int steps) { return false; }

   public final boolean onCycleMode(GeometrySession session, GeometryActionContext context) {
      return onCycleMode(state(session), context);
   }
   protected boolean onCycleMode(S state, GeometryActionContext context) { return false; }

   public final boolean onGizmoDrag(GeometrySession session, GeometryActionContext context,
      AxisGizmo.Operation operation, Axis axis, int steps) {
      return onGizmoDrag(state(session), context, operation, axis, steps);
   }
   protected boolean onGizmoDrag(S state, GeometryActionContext context,
      AxisGizmo.Operation operation, Axis axis, int steps) { return false; }

   public final boolean onInteraction(GeometrySession session, GeometryActionContext context,
      GeometryInteractionTarget.TargetType targetType, int index, GeometryInteractionAction action, PointerGesture gesture) {
      return onInteraction(state(session), context, targetType, index, action, gesture);
   }
   protected boolean onInteraction(S state, GeometryActionContext context,
      GeometryInteractionTarget.TargetType targetType, int index, GeometryInteractionAction action, PointerGesture gesture) { return false; }

   public final boolean onClosePath(GeometrySession session, GeometryActionContext context) {
      return onClosePath(state(session), context);
   }
   protected boolean onClosePath(S state, GeometryActionContext context) { return false; }
   public final boolean confirmsOnRightClick(GeometrySession session) { return allows(session, GeometryAction.CONFIRM); }
   public final GeometryStage stage(GeometrySession session) { return stageState(state(session)); }
   protected final GeometryStage stageState(S state) { return workflow.stage(GeometryWorkflowView.from(state, false)); }
   public final boolean allows(GeometrySession session, GeometryAction action) { return stage(session).allows(action); }
   protected final boolean allows(S state, GeometryAction action) { return stageState(state).allows(action); }

   private GeometryWorkflowView view(GeometrySession session) { return GeometryWorkflowView.from(state(session), false); }
   public final boolean canFill(GeometrySession session) { return workflow.canFill(view(session)); }
   public final GeometryBuildResult build(GeometrySession session, FillMode fillMode, int maxBlocks) {
      return workflow.build(view(session), fillMode, maxBlocks);
   }
   public final Component blockedMessage(GeometrySession session) { return workflow.blockedMessage(view(session)); }
   public final Component pointBlockedMessage(GeometrySession session) { return workflow.pointBlockedMessage(view(session)); }
   public final List<GeometryInteractionTarget> interactionTargets(GeometrySession session) {
      return workflow.interactionTargets(view(session));
   }
}
