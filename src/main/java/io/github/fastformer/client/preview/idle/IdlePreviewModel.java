package io.github.fastformer.client.preview.idle;

import io.github.fastformer.client.render.model.InitialBlockPreview;

/** A resolved first-point candidate shared by outline suppression and drawing. */
public record IdlePreviewModel(InitialBlockPreview block, boolean renderBlockModel) {
   public static final IdlePreviewModel EMPTY = new IdlePreviewModel(null, false);

   public boolean hasOutline() { return block != null && !block.mesh().edges().isEmpty(); }
}
