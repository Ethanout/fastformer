package io.github.fastformer.client.preview.hud;

import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.common.quickshape.mode.LineMode;
import io.github.fastformer.common.quickshape.mode.PolygonVolumeShape;
import io.github.fastformer.common.quickshape.mode.QuickShapeMode;
import io.github.fastformer.common.quickshape.mode.QuickShapeModeRules;
import io.github.fastformer.common.quickshape.mode.QuickShapeStage;
import io.github.fastformer.common.text.TranslatableText;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationStageMode;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Builds session status text solely from captured mode facts. */
public final class SessionHudText {
   private SessionHudText() {}

   public static MutableComponent quickShape(BuildingPreviewSnapshot snapshot) {
      var stage = QuickShapeStage.resolve(snapshot.points().size(), snapshot.faceMode(), snapshot.polygonClosed());
      var status = label(stage.translationKey());
      separator(status);
      if (stage == QuickShapeStage.VOLUME && snapshot.polygonClosed()) {
         appendModes(status, PolygonVolumeShape.values(), snapshot.polygonVolumeShape());
      } else {
         QuickShapeMode selected = switch (stage) {
            case POINT -> snapshot.pointMode();
            case LINE -> snapshot.lineMode();
            case FACE -> snapshot.faceMode();
            case VOLUME -> snapshot.volumeMode();
         };
         appendModes(status, QuickShapeModeRules.allowedModes(stage, snapshot.lineMode()).toArray(QuickShapeMode[]::new), selected);
      }
      return status;
   }

   public static MutableComponent idle(boolean emptyHand, boolean placeable, LineMode lineMode, OperationSelectionMode selectionMode) {
      if (emptyHand || !placeable) return selectionModes(label("fastformer.hud.selection_label"), selectionMode);
      var status = label("fastformer.hud.quick_shape_label");
      separator(status);
      appendModes(status, LineMode.values(), lineMode);
      return status;
   }

   public static MutableComponent selection(boolean localWorkspace, boolean localDraft, boolean ready,
      OperationSelectionMode selectionMode, OperationStageMode stageMode) {
      boolean confirmed = !localWorkspace && !localDraft && ready;
      var status = label(confirmed || localWorkspace && !localDraft
         ? "fastformer.hud.operation_label" : "fastformer.hud.selection_label");
      if (!confirmed) return selectionModes(status, selectionMode);
      separator(status);
      appendModes(status, OperationStageMode.values(), stageMode);
      return status;
   }

   private static MutableComponent selectionModes(MutableComponent status, OperationSelectionMode selected) {
      separator(status);
      appendModes(status, new OperationSelectionMode[] {OperationSelectionMode.CUBOID, OperationSelectionMode.SMART}, selected);
      return status;
   }

   private static MutableComponent label(String key) {
      return Component.translatable(key).withStyle(GeometryPalette.text().style());
   }

   private static void separator(MutableComponent status) {
      status.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
   }

   private static void appendModes(MutableComponent status, TranslatableText[] modes, TranslatableText selected) {
      for (int index = 0; index < modes.length; index++) {
         if (index > 0) status.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
         status.append(Component.translatable(modes[index].translationKey()).withStyle(
            modes[index] == selected ? GeometryPalette.accent().style() : GeometryPalette.muted().style()));
      }
   }
}
