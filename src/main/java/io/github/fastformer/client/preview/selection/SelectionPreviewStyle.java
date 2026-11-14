package io.github.fastformer.client.preview.selection;

import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.common.selection.OperationSelectionVolume;

/** Selection drawing constants; frame timing is passed explicitly by the caller. */
final class SelectionPreviewStyle {
   static final float GHOST_FACE_ALPHA_MIN = 0.10F;
   static final float GHOST_FACE_ALPHA_MAX = PreviewStyle.FACE_ALPHA;
   static final float SELECTION_HIGHLIGHT_ALPHA = PreviewStyle.FACE_HOVER_ALPHA;
   static final float GHOST_OUTLINE_ALPHA_MIN = 0.70F;
   static final float GHOST_OUTLINE_ALPHA_MAX = PreviewStyle.OUTLINE_ALPHA;
   static final double SELECTION_FACE_INFLATE = OperationSelectionVolume.RAYCAST_INFLATE;
   static final double EPSILON = 1.0E-7;
   private SelectionPreviewStyle() {}
   static float inkRed() { return GeometryPalette.ink().red(); }
   static float inkGreen() { return GeometryPalette.ink().green(); }
   static float inkBlue() { return GeometryPalette.ink().blue(); }
}
