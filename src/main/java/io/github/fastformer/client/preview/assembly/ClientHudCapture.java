package io.github.fastformer.client.preview.assembly;

import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.preview.hud.HudModel;
import io.github.fastformer.client.feedback.FeedbackFrame;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.interaction.intent.WorkspacePointerPrompt;
import io.github.fastformer.client.render.WorkspaceSubmissionHud;
import io.github.fastformer.client.preview.ClientPreviewSystem;
import io.github.fastformer.client.render.hud.GizmoHudTextFormatter;
import io.github.fastformer.client.feedback.InteractionFeedback;
import io.github.fastformer.client.feedback.AxisFeedback;
import io.github.fastformer.client.feedback.ScrollFeedbackData;
import io.github.fastformer.client.render.model.*;
import io.github.fastformer.client.session.preview.ClientPreviewState;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.GeometryNumbers;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionHit;
import io.github.fastformer.common.geometry.gizmo.GizmoTextComponent;
import io.github.fastformer.common.geometry.gizmo.GizmoTextContext;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.common.quickshape.mode.FaceMode;
import io.github.fastformer.common.quickshape.mode.LineMode;
import io.github.fastformer.common.quickshape.mode.QuickShapeStage;
import io.github.fastformer.common.quickshape.mode.RaycastPlacement;
import io.github.fastformer.common.quickshape.mode.VolumeMode;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import io.github.fastformer.client.preview.hud.SessionHudText;
import io.github.fastformer.client.gizmo.GizmoColors;
import io.github.fastformer.client.interaction.SmartSelectionEditView;
import io.github.fastformer.client.operation.pointer.RemoteSelectionPointer;
import io.github.fastformer.client.operation.selection.SelectionToolPreference;
import io.github.fastformer.client.operation.selection.tool.SelectionTools;
import io.github.fastformer.client.preview.hud.SelectionToolDisplay;
import io.github.fastformer.common.geometry.preview.GeometryTextBlock;

/** Samples live session facts once; the resulting HUD model is drawn without querying them. */
public final class ClientHudCapture {
   private static final double EPSILON = 1.0E-7;
   private ClientHudCapture() {}
   private static ClientPreviewState previews() { return ClientSessionSystem.instance().previews(); }
   private static RemoteSelectionPointer remotePointer() {
      return ClientSessionSystem.instance().operations().remotePointer();
   }
   private static InteractionFeedback feedback() { return ClientSessionSystem.instance().inputSession().feedback(); }
   private static boolean operationActive() {
      var operations = ClientSessionSystem.instance().operations();
      return operations.active() || operations.selectionSessionActive();
   }
   private static boolean operationSelectionReady() { return ClientSessionSystem.instance().operations().operationSelectionReady(); }
   private static boolean operationSelectionConfirmed() { return ClientSessionSystem.instance().operations().operationSelectionConfirmed(); }
   private static Optional<OperationInteractionIntent> operationInteractionIntent() {
      return ClientSessionSystem.instance().operations().pointer().frame().target();
   }
   private static OperationInteractionIntent.Gizmo operationWorkspaceGizmoHit() {
      return ClientSessionSystem.instance().operations().pointer().frame().gizmo();
   }
   private static boolean buildingRaycastSubmodeAvailable() {
      return ClientSessionSystem.instance().quickShapePointer().raycastSubmodeAvailable(
         previews().geometry().active() || previews().operation().active());
   }
   public static HudModel capture(
      Minecraft minecraft, GeometryPreviewPlan geometryPlan, AxisGizmo remoteGizmo, long frameId, long now, boolean visible
   ) {
      if (!visible) return HudModel.EMPTY;
      var left = new ArrayList<HudModel.Text>();
      var right = new ArrayList<HudModel.Text>();
      var bottom = new ArrayList<Component>();
      var fill = FastPlaceClientInput.currentSession().smartFill();
      if (fill.active()) {
         left.add(hudText(Component.translatable(fill.statusKey(), fill.blocks().size()), 8, GeometryPalette.text().argb()));
         left.add(hudText(Component.translatable(fill.ready() ? "fastformer.fill.controls" : "fastformer.fill.cancel"), 20, GeometryPalette.muted().argb()));
         return hudModel(1, left, right, bottom, List.of(), null, 0);
      }
      if (QuickReplaceMode.active()) {
         left.add(hudText(Component.translatable("fastformer.quick_replace.active"), 8, GeometryPalette.text().argb()));
         left.add(hudText(Component.translatable(QuickReplaceMode.canReplace(minecraft)
            ? "fastformer.quick_replace.controls" : "fastformer.quick_replace.hold_block"), 20, GeometryPalette.muted().argb()));
         if (previews().activity().task()) left.add(hudText(Component.translatable(previews().activity().translationKey()), 32, GeometryPalette.valid().argb()));
         return hudModel(1, left, right, bottom, List.of(), null, 0);
      }
      var building = previews().building();
      var gizmoInput = gizmoHudInput(geometryPlan, remoteGizmo);
      var gizmoHit = capturedGizmoHit(gizmoInput);
      Object gizmoTarget = gizmoHit == null ? null : gizmoHit.handle().axis().name() + ":" + gizmoHit.handle().operation().name();
      Object scrollTarget = minecraft.hitResult instanceof BlockHitResult hit ? hit.getBlockPos().immutable() : null;
      var feedbackData = building.active() && building.lineMode() == LineMode.FREE_SCROLL && building.points().size() <= 1
         ? feedback().coordinates(building.freeScrollOffset()) : scrollFeedbackData();
      var frame = feedback().capture(frameId, now, building, scrollTarget, gizmoTarget, feedbackData, gizmoInput.dragAxis() == null);
      boolean reconnect = ClientSessionSystem.instance().operations().reconnectRestorePending() || previews().reconnectRestorePending();
      boolean pending = ClientSessionSystem.instance().operations().workspaceSubmissionPending();
      boolean task = previews().activity().task();
      var submission = WorkspaceSubmissionHud.plan(pending, task, false, reconnect);
      if (!WorkspaceSubmissionHud.hudActive(reconnect, building.enabled(), frame.persistentFreeScroll(),
         previews().geometry().active(), operationActive(), task, false, pending)) return HudModel.EMPTY;
      float opacity = task || pending || reconnect ? 1 : InteractionContext.previewVisibility(minecraft);
      Component fillMode = Component.translatable((previews().geometry().active()
         ? previews().geometry().fillMode() : building.fillMode()).translationKey());
      String fillKey = WorkspaceSubmissionHud.fillModeKey(pending);
      Component modeText = (WorkspaceSubmissionHud.FILL_MODE_STATUS_KEY.equals(fillKey)
         ? Component.translatable(fillKey, fillMode)
         : Component.translatable(fillKey, fillMode, keyName(minecraft.options.keySwapOffhand))).withStyle(GeometryPalette.text().style());
      if (!ClientSessionSystem.instance().modeView().idleActionsAllowed()) left.add(hudText(modeText, 8, -1));
      Component embedded = buildingRaycastHint();
      if (embedded != null && !task && !submission.suppressActionPrompts()) right.add(hudText(embedded, 8, GeometryPalette.muted().argb()));
      if (task) {
         MutableComponent text = Component.translatable(previews().activity().translationKey()).withStyle(GeometryPalette.valid().style());
         if (WorkspaceSubmissionHud.showsCancelHint(previews().activity(), pending)) text.append(Component.literal(" ")
            .append(Component.translatable("fastformer.activity.cancel_hint")).withStyle(GeometryPalette.muted().style()));
         left.add(hudText(text, 20, -1));
      } else if (submission.showsWaitingLine()) left.add(hudText(Component.translatable(submission.waitingKey())
         .withStyle(GeometryPalette.accent().style()), submission.waitingLineY(), -1));
      if (reconnect) left.add(hudText(Component.translatable("fastformer.message.reconnect_restore_prompt"), 32, GeometryPalette.accent().argb()));
      if (!task) captureSessionHud(minecraft, geometryPlan, submission.suppressActionPrompts(), left, right, bottom);
      var scroll = !task || frame.persistentFreeScroll() ? frame.scroll() : null;
      var crosshair = new ArrayList<HudModel.Line>();
      if (!task && geometryPlan != null) for (var block : geometryPlan.textBlocks()) {
         if (block.visible() && block.placement() == GeometryTextBlock.Placement.CROSSHAIR
            && GeometryTextBlock.hasContent(block.content())) crosshair.add(hudLine(block.content(), -1));
      }
      crosshair.addAll(captureCrosshairHud(minecraft, task ? null : geometryPlan, frame, gizmoInput, gizmoHit));
      return hudModel(opacity, left, right, bottom, crosshair, scroll, frame.scrollAlpha());
   }

   private static void captureSessionHud(Minecraft minecraft, GeometryPreviewPlan plan, boolean suppress,
      List<HudModel.Text> left,
      List<HudModel.Text> right, List<Component> bottom) {
      if (plan != null) {
         int leftY = 32, rightY = 32;
         var hints = new ArrayList<Component>();
         var statuses = new ArrayList<Component>();
         for (var block : plan.textBlocks()) {
            if (!block.visible() || !GeometryTextBlock.hasContent(block.content())
               || Set.of("stage", "mode", "value").contains(block.id())) continue;
            Component content = GeometryTextBlock.HINT_ID.equals(block.id())
               ? block.content().copy().withStyle(GeometryPalette.muted().style()) : block.content();
            switch (block.placement()) {
               case TOP_LEFT -> { left.add(hudText(content, leftY, -1)); leftY += PreviewStyle.HUD_LINE_HEIGHT; }
               case TOP_RIGHT -> { right.add(hudText(content, rightY, -1)); rightY += PreviewStyle.HUD_LINE_HEIGHT; }
               case BOTTOM_HINT -> hints.add(content);
               case BOTTOM_CENTER -> statuses.add(content);
               case CROSSHAIR -> { }
            }
         }
         bottom.addAll(hints);
         bottom.addAll(statuses);
         return;
      }
      Component primary;
      Component secondary = null;
      if (operationActive()) {
         primary = operationBottomStatus();
         if (!suppress) secondary = Component.translatable(ClientSessionSystem.instance().operations().selectionEdits().smartSampling()
            ? "fastformer.hud.smart_sampling" : SmartSelectionEditView.active(minecraft)
            ? "fastformer.hud.smart_isolation_controls" : ClientSessionSystem.instance().operations().selectionEdits().smartTool()
            ? "fastformer.hud.smart_controls" : "fastformer.hud.selection_controls").withStyle(GeometryPalette.muted().style());
      } else { primary = buildingBottomStatus(); if (!suppress) secondary = buildingBottomHint(); }
      if (secondary != null) bottom.add(secondary);
      if (primary != null) bottom.add(primary);
   }

   private static HudModel hudModel(float opacity,
      List<HudModel.Text> left, List<HudModel.Text> right,
      List<Component> bottom, List<HudModel.Line> crosshair, ScrollFeedbackData scroll, int alpha) {
      return new HudModel(true, opacity, left, right, bottom, crosshair, scroll, alpha, GeometryPalette.text().argb());
   }

   private static HudModel.Text hudText(Component content, int y, int color) {
      return new HudModel.Text(content, y, color);
   }

   private static HudModel.Line hudLine(Component content, int color) {
      return new HudModel.Line(List.of(
         new HudModel.Run(content, color, 1, 0)));
   }

   private static AxisGizmo.Hit capturedGizmoHit(GizmoHudInput input) {
      if (input.gizmo() == null) return null;
      for (var handle : input.gizmo().handles()) if (handle.hovered()) return new AxisGizmo.Hit(handle, input.gizmo().handleCenter(handle), 0, 0);
      return null;
   }

   private static double axisComponent(Vec3 value, Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
   }

   private static double geometryGizmoValue(
      GeometryPreviewPlan geometryPlan, Axis axis, AxisGizmo.Operation operation
   ) {
      return ClientSessionSystem.instance().geometryPointer().gizmoValue(geometryPlan, axis, operation);
   }

   private static MutableComponent buildingBottomStatus() {
      if (!previews().building().active()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.player == null || minecraft.screen != null || QuickReplaceMode.active()
            || !previews().building().enabled()) return null;
         return idleBottomStatus(minecraft.player.getMainHandItem().isEmpty(),
            PlaceableItems.isPlaceable(minecraft.player.getMainHandItem()));
      }

      return SessionHudText.quickShape(previews().building());
   }

   private static MutableComponent buildingRaycastHint() {
      if (!previews().building().active() || previews().geometry().active()
         || previews().operation().active()
         || !buildingRaycastSubmodeAvailable()
         || !PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession())
         || previews().building().raycastPlacement() == RaycastPlacement.EMBEDDED) {
         return null;
      }
      return Component.translatable("fastformer.hud.raycast_embedded_hint");
   }

   private static MutableComponent buildingBottomHint() {
      if (!previews().building().active()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.player == null || minecraft.screen != null || QuickReplaceMode.active()
            || !previews().building().enabled()) return null;
         if (ClientPreviewSystem.instance().idleFrame().block() != null) {
            return (PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
               ? Component.translatable("fastformer.hud.idle_entry_far_placement", keyName(minecraft.options.keyUse), keyName(minecraft.options.keyPickItem))
               : Component.translatable("fastformer.hud.idle_entry_far", keyName(minecraft.options.keyUse)))
               .withStyle(GeometryPalette.muted().style());
         }
         return Component.translatable("fastformer.hud.idle_entry", keyName(minecraft.options.keyUse))
            .withStyle(GeometryPalette.muted().style());
      }
      QuickShapeStage stage = effectiveStage(previews().building());
      if (stage == QuickShapeStage.FACE && previews().building().faceMode() == FaceMode.POLYGON && !previews().building().polygonClosed()) {
         return Component.translatable("fastformer.message.polygon_close_hint").withStyle(GeometryPalette.muted().style());
      }
      return Component.translatable(
         stage == QuickShapeStage.VOLUME
            ? previews().building().polygonClosed()
               ? "fastformer.message.building_hint_height"
               : "fastformer.message.building_hint_points"
            : "fastformer.message.building_hint_points"
      ).withStyle(GeometryPalette.muted().style());
   }

   private static List<HudModel.Line> captureCrosshairHud(
      Minecraft minecraft, GeometryPreviewPlan geometryPlan, FeedbackFrame frame, GizmoHudInput hudInput, AxisGizmo.Hit capturedHit
   ) {
      var lines = new ArrayList<HudModel.Line>();
      if (minecraft.screen != null || minecraft.player == null) {
         return lines;
      }
      boolean workspaceLocked = ClientSessionSystem.instance().operations().active() && ClientSessionSystem.instance().operations().workspace().locked();
      GeometryInteractionHit interactionHit = ClientSessionSystem.instance().geometryPointer().interactionHit();
      // This text offers a pointer action. The locked workspace refuses that
      // action, so draw the text only while the workspace accepts new actions.
      if (interactionHit != null && capturedHit == null
         && WorkspacePointerPrompt.acceptsNewAction(workspaceLocked)) {
         lines.add(hudLine(interactionHit.hoverText(), GeometryPalette.text().argb()));
      }
      OperationInteractionIntent operationTarget = operationInteractionIntent().orElse(null);
      if (capturedHit == null
         && remotePointer().operationPointUnderCrosshairIndex() < 0
         && operationTarget instanceof OperationInteractionIntent.Face face) {
         for (Component line : WorkspacePointerPrompt.crosshairActionLines(face, workspaceLocked)) {
            lines.add(hudLine(line, GeometryPalette.text().argb()));
         }
         return lines;
      }
      Axis dragAxis = hudInput.dragAxis();
      Axis axis = dragAxis;
      AxisGizmo.Operation operation = dragAxis == null ? null : hudInput.dragOperation();
      AxisGizmo displayedGizmo = hudInput.gizmo();
      boolean retainedGizmo = false;
      boolean hoveredGizmo = false;
      int gizmoAlpha = 255;
      if (axis == null) {
         AxisGizmo.Hit hit = capturedHit;
         if (hit != null) {
            axis = hit.handle().axis();
            operation = hit.handle().operation();
            hoveredGizmo = true;
            gizmoAlpha = frame.gizmoAlpha();
            retainedGizmo = gizmoAlpha > 0 && axis == frame.lastAxis();
         } else {
            gizmoAlpha = frame.gizmoAlpha();
            if (gizmoAlpha > 0) {
               axis = frame.lastAxis();
               operation = frame.lastOperation();
               retainedGizmo = axis != null;
            }
         }
      }
      if (WorkspacePointerPrompt.acceptsNewAction(workspaceLocked)
         && axis != null && operation != null) {
         int axisColor = gizmoAxisHudColor(axis);
         if (retainedGizmo && !hoveredGizmo) {
            axisColor = withAlpha(axisColor, gizmoAlpha);
         }
         GizmoTextComponent textComponent = displayedGizmo == null
            ? GizmoTextComponent.none()
            : displayedGizmo.textComponent();
         boolean valueAvailable = dragAxis != null || retainedGizmo;
         boolean useTemplate = !textComponent.empty() && (hoveredGizmo || dragAxis != null);
         if (useTemplate) {
            double baseValue = dragAxis != null
               ? hudInput.dragBaseValue()
               : frame.lastBaseValue();
            double currentValue = gizmoHudValue(hudInput, geometryPlan, axis, operation);
            int steps = dragAxis != null ? hudInput.dragSteps() : frame.lastSteps();
            GizmoTextContext context = hudInput.operationSelection() && operationSelectionReady()
               ? GizmoHudTextFormatter.operationTransformTextContext(axis, operation, baseValue, currentValue, steps)
               : GizmoHudTextFormatter.textContext(axis, operation, baseValue, currentValue, steps);
            String text = textComponent.render(context, valueAvailable);
            if (!text.isBlank()) {
               lines.add(hudLine(Component.literal(text), axisColor));
            }
         } else {
            var runs = new ArrayList<HudModel.Run>();
            runs.add(new HudModel.Run(Component.literal(axis.name()), axisColor, 1, 0));
            if (operation != AxisGizmo.Operation.MOVE || !valueAvailable) runs.add(
               new HudModel.Run(Component.translatable(GizmoHudTextFormatter.transformLabelKey(operation)), axisColor, 0.75F, 3));
            if (valueAvailable) {
               double baseValue = dragAxis != null
                  ? hudInput.dragBaseValue()
                  : frame.lastBaseValue();
               double currentValue = gizmoHudValue(hudInput, geometryPlan, axis, operation);
               String value = GizmoHudTextFormatter.formatValue(
                  dragAxis != null ? hudInput.dragOperation() : frame.lastOperation(),
                  baseValue,
                  currentValue,
                  dragAxis != null ? hudInput.dragSteps() : frame.lastSteps()
               );
               int valueColor = retainedGizmo ? withAlpha(axisColor, gizmoAlpha) : axisColor;
               runs.add(new HudModel.Run(Component.literal(value), valueColor, 1, 3));
            }
            lines.add(new HudModel.Line(runs));
         }
      }
      return lines;
   }

   private static GizmoHudInput gizmoHudInput(GeometryPreviewPlan geometryPlan, AxisGizmo remoteGizmo) {
      if (ClientSessionSystem.instance().operations().active()) {
         OperationInteractionIntent.Gizmo target = operationWorkspaceGizmoHit();
         var drag = FastPlaceClientInput.currentSession().selectionGestureSnapshot().gizmo();
         return new GizmoHudInput(
            target == null ? null : target.gizmo(),
            drag == null ? null : drag.axis(),
            drag == null ? null : drag.operation(),
            PointerDragSnapshotView.operationBaseValue(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationSteps(FastPlaceClientInput.currentSession()),
            true,
            false
         );
      }
      if (previews().operation().active()) {
         return new GizmoHudInput(
            remoteGizmo,
            PointerDragSnapshotView.operationAxis(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationOperation(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationBaseValue(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationSteps(FastPlaceClientInput.currentSession()),
            true,
            true
         );
      }
      return new GizmoHudInput(
         geometryPlan == null ? null : geometryPlan.gizmo(),
         PointerDragSnapshotView.axis(FastPlaceClientInput.currentSession()),
         PointerDragSnapshotView.operation(FastPlaceClientInput.currentSession()),
         PointerDragSnapshotView.baseValue(FastPlaceClientInput.currentSession()),
         PointerDragSnapshotView.steps(FastPlaceClientInput.currentSession()),
         false,
         false
      );
   }

   private static double gizmoHudValue(
      GizmoHudInput input,
      GeometryPreviewPlan geometryPlan,
      Axis axis,
      AxisGizmo.Operation operation
   ) {
      if (!input.operationSelection()) {
         return geometryGizmoValue(geometryPlan, axis, operation);
      }
      if (operationSelectionReady()) {
         return remotePointer().operationGizmoValue(axis, operation);
      }
      double value = input.gizmo() == null
         ? input.dragBaseValue()
         : axisComponent(input.gizmo().center(), axis);
      if (axis == input.dragAxis()
         && operation == AxisGizmo.Operation.MOVE
         && input.dragSteps() != 0
         && Math.abs(value - input.dragBaseValue()) < EPSILON) {
         return input.dragBaseValue() + input.dragSteps();
      }
      return value;
   }

   private static int gizmoAxisHudColor(Axis axis) {
      return 0xFF000000 | GizmoColors.axis(axis);
   }

   private static ScrollFeedbackData scrollFeedbackData() {
      if (previews().operation().active() && operationSelectionReady()) {
      BlockPos value = operationSelectionConfirmed() && PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession())
            ? previews().operation().stackVector()
            : previews().operation().translation();
         return feedback().coordinates(value);
      }
      if (previews().geometry().active() && previews().geometry().mode() == GeometryMode.CONE_PRISM) {
         Vec3 offset = previews().geometry().coneTopOffset();
         return new ScrollFeedbackData(
            List.of(
               new AxisFeedback("X", GeometryNumbers.fixed(offset.x, 2), feedback().axisColor(Axis.X)),
               new AxisFeedback("Z", GeometryNumbers.fixed(offset.z, 2), feedback().axisColor(Axis.Z))
            ),
            ""
         );
      }
      if (previews().building().active()) {
         BuildingPreviewSnapshot building = previews().building();
         // Keep the free-scroll payload aligned with persistent visibility.
         // effectiveStage() can briefly describe the previous server snapshot
         // while the active line session already owns the scroll offset.
         if (feedback().freeScrollSession() && building.points().size() <= 1) {
            BlockPos offset = building.points().size() == 1
               ? building.freeScrollOffset() : feedback().freeScrollOffset();
            ScrollFeedbackData data = feedback().coordinates(offset);
            return data;
         }
         QuickShapeStage stage = effectiveStage(building);
         if (stage == QuickShapeStage.FACE && previews().building().faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
               return new ScrollFeedbackData(
                  List.of(),
                  GeometryNumbers.fixed(
                     ClientSessionSystem.instance().quickShapePointer().faceBaseOffsetValue(player.getViewVector(1.0F)),
                     0
                  )
               );
            }
         }
         if (stage == QuickShapeStage.VOLUME && previews().building().volumeMode() == VolumeMode.FREE) {
            return previews().building().volumeMode() == VolumeMode.FREE
               ? feedback().coordinates(previews().building().volumeBaseOffset())
               : new ScrollFeedbackData(List.of(), formatScalar(previews().building().volumeBaseOffset()));
         }
      }
      // A wheel request can produce one or more inactive snapshots while the
      // server applies the new offset. Keep rendering the latched value for
      // that gap; otherwise the HUD disappears even though the session is
      // still valid and the feedback component keeps it visible.
      if (feedback().freeScrollSession()) {
         ScrollFeedbackData data = feedback().freeScrollData();
         return data != null ? data : feedback().coordinates(feedback().freeScrollOffset());
      }
      return null;
   }

   private static int withAlpha(int color, int alpha) {
      return (Math.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
   }

   private static String formatScalar(Vec3 offset) {
      double value = Math.abs(offset.x) >= Math.abs(offset.y) && Math.abs(offset.x) >= Math.abs(offset.z)
         ? offset.x
         : Math.abs(offset.y) >= Math.abs(offset.z) ? offset.y : offset.z;
      return GeometryNumbers.fixed(value, 0);
   }

   private static QuickShapeStage effectiveStage(BuildingPreviewSnapshot snapshot) {
      return QuickShapeStage.resolve(snapshot.points().size(), snapshot.faceMode(), snapshot.polygonClosed());
   }

   private static String keyName(KeyMapping mapping) {
      return mapping.getTranslatedKeyMessage().getString();
   }

   public static MutableComponent idleBottomStatus(boolean emptyHand, boolean placeable) {
      return SessionHudText.idle(emptyHand, placeable, previews().building().lineMode(),
         selectionToolDisplays(), selectionToolDisplay(SelectionToolPreference.id()));
   }
   private static MutableComponent operationBottomStatus() {
      var operations = ClientSessionSystem.instance().operations();
      return SessionHudText.selection(operations.active(), operations.selectionEdits().selectionDraftActive(),
         operations.operationSelectionReady(), selectionToolDisplays(),
         selectionToolDisplay(operations.selectionEdits().selectionDraft().toolId()), previews().operation().operationStageMode());
   }
   private static java.util.List<SelectionToolDisplay> selectionToolDisplays() {
      return SelectionTools.choices().stream().map(tool -> new SelectionToolDisplay(tool.id(), tool.translationKey())).toList();
   }
   private static SelectionToolDisplay selectionToolDisplay(net.minecraft.resources.ResourceLocation id) {
      var tool = SelectionTools.require(id);
      return new SelectionToolDisplay(tool.id(), tool.translationKey());
   }
}
