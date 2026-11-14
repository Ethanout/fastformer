package io.github.fastformer.client.preview;

import com.mojang.logging.LogUtils;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.lifecycle.ClientBackgroundTasks;
import io.github.fastformer.client.preview.geometry.GeometryPreviewMode;
import io.github.fastformer.client.preview.idle.IdlePreviewMode;
import io.github.fastformer.client.preview.idle.IdlePreviewModel;
import io.github.fastformer.client.preview.idle.IdlePreviewInput;
import io.github.fastformer.client.preview.quickshape.QuickShapePreviewMode;
import io.github.fastformer.client.preview.selection.SelectionPreviewMode;
import io.github.fastformer.client.preview.frame.PreviewFrame;
import io.github.fastformer.client.preview.block.BlockPreviewInput;
import io.github.fastformer.client.preview.block.BlockPreviewMode;
import io.github.fastformer.client.preview.block.BlockPreviewModel;
import io.github.fastformer.client.session.ClientSessionSystem;
import org.slf4j.Logger;

/** Owns preview generation capabilities, asynchronous result consumption, and their shared diagnostics. */
public final class ClientPreviewSystem {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final long FAILURE_INTERVAL_NANOS = 5_000_000_000L;
   private final QuickShapePreviewMode quickShape;
   private final GeometryPreviewMode geometry;
   private final IdlePreviewMode idle = new IdlePreviewMode();
   private final SelectionPreviewMode selection = new SelectionPreviewMode();
   private PreviewFrame frame = PreviewFrame.EMPTY;
   private IdlePreviewModel idleFrame = IdlePreviewModel.EMPTY;
   private final BlockPreviewMode fill = new BlockPreviewMode(0.55F);
   private final BlockPreviewMode replace = new BlockPreviewMode(0.42F);
   private BlockPreviewModel fillFrame = BlockPreviewModel.EMPTY;
   private BlockPreviewModel replaceFrame = BlockPreviewModel.EMPTY;
   private long lastFailureAt;

   private ClientPreviewSystem() {
      var sessions = ClientSessionSystem.instance();
      quickShape = new QuickShapePreviewMode(sessions.previews(),
         () -> PointerDragSnapshotView.modifierHeld(sessions.inputSession()), this::logFailure,
         ClientBackgroundTasks.serial("preview-generation", 16));
      geometry = new GeometryPreviewMode(this::logFailure, ClientBackgroundTasks.serial("preview-mesh", 16));
   }

   private static final class Holder {
      private static final ClientPreviewSystem INSTANCE = new ClientPreviewSystem();
   }

   public static ClientPreviewSystem instance() { return Holder.INSTANCE; }
   public QuickShapePreviewMode quickShape() { return quickShape; }
   public GeometryPreviewMode geometry() { return geometry; }
   public SelectionPreviewMode selection() { return selection; }
   public PreviewFrame frame() { return frame; }
   public void publish(PreviewFrame frame) { this.frame = java.util.Objects.requireNonNull(frame, "frame"); }
   public IdlePreviewMode idle() { return idle; }
   public IdlePreviewModel idleFrame() { return idleFrame; }
   public void captureIdleFrame(IdlePreviewInput input) { idleFrame = idle.buildModel(input); }
   public BlockPreviewMode fill() { return fill; }
   public BlockPreviewMode replace() { return replace; }
   public BlockPreviewModel fillFrame() { return fillFrame; }
   public BlockPreviewModel replaceFrame() { return replaceFrame; }
   public void captureToolFrames(BlockPreviewInput fillInput, BlockPreviewInput replaceInput) {
      fillFrame = fill.buildModel(fillInput);
      replaceFrame = replace.buildModel(replaceInput);
   }

   public void tick() { quickShape.tick(); }

   /** Theme changes invalidate generated resources, not the facts already captured for this frame. */
   public void invalidateResources() {
      quickShape.clear();
      geometry.clear();
      idle.clear();
      fill.clear();
      replace.clear();
      selection.clear();
      io.github.fastformer.client.render.PreviewMaterialRenderer.clear();
   }

   public void clear() {
      invalidateResources();
      idleFrame = IdlePreviewModel.EMPTY;
      fillFrame = BlockPreviewModel.EMPTY;
      replaceFrame = BlockPreviewModel.EMPTY;
      frame = PreviewFrame.EMPTY;
   }

   private void logFailure(String message, Throwable failure) {
      long now = System.nanoTime();
      if (lastFailureAt == 0L || now < lastFailureAt || now - lastFailureAt >= FAILURE_INTERVAL_NANOS) {
         lastFailureAt = now;
         LOGGER.warn(message, failure);
      }
   }
}
