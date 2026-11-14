package io.github.fastformer.client.geometry.presentation;

import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.common.geometry.preview.GeometryStatusText;
import io.github.fastformer.common.geometry.preview.GeometryTextBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;

/** Caches one owner's themed text while leaving per-frame gizmo presentation to the caller. */
public final class GeometryTextPresentation {
   private GeometryPreviewPlan source;
   private GeometryPreviewPlan presented;
   private long paletteRevision = -1;

   public GeometryPreviewPlan present(GeometryPreviewPlan plan) {
      java.util.Objects.requireNonNull(plan, "plan");
      long revision = GeometryPalette.revision();
      if (source != plan || paletteRevision != revision) {
         presented = themed(plan);
         source = plan;
         paletteRevision = revision;
      }
      return presented;
   }

   public void clear() {
      source = null;
      presented = null;
      paletteRevision = -1;
   }

   private static GeometryPreviewPlan themed(GeometryPreviewPlan plan) {
      var styles = new GeometryStatusText.Styles(GeometryPalette.text().style(), GeometryPalette.muted().style(),
         GeometryPalette.accent().style(), Style.EMPTY.applyFormat(ChatFormatting.DARK_GRAY));
      return plan.withTextBlocks(plan.textBlocks().stream().map(block -> present(plan, block, styles)).toList());
   }

   private static GeometryTextBlock present(GeometryPreviewPlan plan, GeometryTextBlock block, GeometryStatusText.Styles styles) {
      if (block.source() != GeometryTextBlock.ContentSource.STAGE_BAR) return block;
      var content = GeometryStatusText.composeStageBar(plan.stage(), plan.stageDisplay(), plan.variant(),
         plan.textVisible(GeometryTextBlock.STAGE_ID), plan.textVisible(GeometryTextBlock.MODE_ID),
         plan.textVisible(GeometryTextBlock.VALUE_ID), styles);
      return new GeometryTextBlock(block.id(), block.placement(), content, block.visible(), block.source());
   }
}
