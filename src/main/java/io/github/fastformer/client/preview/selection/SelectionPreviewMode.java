package io.github.fastformer.client.preview.selection;

import io.github.fastformer.client.preview.frame.PreviewMode;
import io.github.fastformer.client.preview.frame.PreviewDrawContext;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import io.github.fastformer.client.render.hud.BottomHudLayout;

/** Owns selection preview drawing, smart-selection caches and their cleanup. */
public final class SelectionPreviewMode implements PreviewMode<SelectionPreviewInput, SelectionPreviewModel> {
   public void beginFrame() { SmartSelectionRenderer.beginVisualFrame(); }
   @Override public SelectionPreviewModel buildModel(SelectionPreviewInput input) { return SelectionPreviewModels.capture(input); }
   @Override public void drawWorld(WorldPreviewFrame frame, SelectionPreviewModel model) {
      var input = model.input();
      var graphics = PreviewDrawContext.capture(frame.minecraft());
      if (input.serverSelection()) OperationPreviewRenderer.renderOperationSelection(frame, graphics, input.snapshot(), model);
      if (input.workspace()) OperationPreviewRenderer.renderClientOperationWorkspace(frame, graphics, input.pointer(), model);
      OperationPreviewRenderer.renderDraft(frame, graphics, input.pointer(), model);
      OperationPreviewRenderer.renderSelectionCreationCandidate(frame, graphics, model);
   }
   @Override public void drawAfterLevel(WorldPreviewFrame frame, SelectionPreviewModel model) {
      SmartSelectionIsolationRenderer.render(frame, PreviewDrawContext.capture(frame.minecraft()), model);
   }
   public void drawHud(GuiGraphics graphics, Font font, BottomHudLayout bottom) {
      SmartSelectionRenderer.drawHud(graphics, font, bottom);
   }
   @Override public void clear() { SmartSelectionRenderer.clear(); SmartSelectionIsolationRenderer.clear(); }
}
