package io.github.fastformer.client.render;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.client.render.model.ScrollFeedbackData;
import net.minecraft.core.BlockPos;

/** Owns transient HUD timers and hover acceleration for the preview client. */
public final class PreviewFeedbackState {
   static final long FREE_SCROLL_TRANSITION_GRACE_NANOS = 1_000_000_000L;
   private final HudFadeTimer scroll = new HudFadeTimer(1_000_000_000L, 400_000_000L);
   private final HudFadeTimer gizmo = new HudFadeTimer(1_000_000_000L, 400_000_000L);
   // A short dwell makes repeated adjustments responsive while still requiring
   // the crosshair to remain on the same target.
   private final HoverDwellTracker scrollDwell = new HoverDwellTracker(100_000_000L, 3.0);
   private final HoverDwellTracker gizmoDwell = new HoverDwellTracker(100_000_000L, 3.0);
   private boolean freeScrollSession;
   private long freeScrollTransitionGraceUntil;
   private BlockPos freeScrollOffset = BlockPos.ZERO;
   private ScrollFeedbackData freeScrollData;
   private AxisGizmo.Axis lastGizmoAxis;
   private AxisGizmo.Operation lastGizmoOperation;
   private int lastGizmoSteps;
   private double lastGizmoBaseValue;

   public HudFadeTimer scroll() { return this.scroll; }
   public HudFadeTimer gizmo() { return this.gizmo; }
   public HoverDwellTracker scrollDwell() { return this.scrollDwell; }
   public HoverDwellTracker gizmoDwell() { return this.gizmoDwell; }
   public boolean freeScrollSession() { return this.freeScrollSession; }
   public BlockPos freeScrollOffset() { return this.freeScrollOffset; }
   public ScrollFeedbackData freeScrollData() { return this.freeScrollData; }

   /**
    * Keeps the free-scroll label visible across the short snapshot gaps caused by
    * wheel updates, but ends it once another active building state takes over.
    */
   public boolean refreshFreeScrollSession(
      boolean activeBuilding, boolean activeFreeScroll, BlockPos offset, long now
   ) {
      if (activeFreeScroll) {
         this.beginFreeScrollSession(offset);
         return true;
      }
      if (!this.freeScrollSession) {
         return false;
      }
      if (activeBuilding && !this.freeScrollTransitionProtected(now)) {
         this.endFreeScrollSession();
         return false;
      }
      return true;
   }

   public void protectFreeScrollTransition(long now) {
      this.freeScrollTransitionGraceUntil = now + FREE_SCROLL_TRANSITION_GRACE_NANOS;
   }

   public void beginFreeScrollSession(BlockPos offset) {
      BlockPos next = offset == null ? BlockPos.ZERO : offset.immutable();
      if (!this.freeScrollSession) {
         this.freeScrollSession = true;
         this.freeScrollData = null;
      }
      this.freeScrollOffset = next;
   }

   public void rememberFreeScrollData(ScrollFeedbackData data) {
      if (data != null) {
         this.freeScrollData = data;
      }
   }

   public void endFreeScrollSession() {
      this.freeScrollSession = false;
      this.freeScrollTransitionGraceUntil = 0L;
      this.freeScrollOffset = BlockPos.ZERO;
      this.freeScrollData = null;
   }

   private boolean freeScrollTransitionProtected(long now) {
      return now - this.freeScrollTransitionGraceUntil < 0L;
   }
   public AxisGizmo.Axis lastGizmoAxis() { return this.lastGizmoAxis; }
   public AxisGizmo.Operation lastGizmoOperation() { return this.lastGizmoOperation; }
   public int lastGizmoSteps() { return this.lastGizmoSteps; }
   public double lastGizmoBaseValue() { return this.lastGizmoBaseValue; }

   public void noteGizmo(AxisGizmo.Axis axis, AxisGizmo.Operation operation, int steps, double baseValue) {
      this.lastGizmoAxis = axis;
      this.lastGizmoOperation = operation;
      this.lastGizmoSteps = steps;
      this.lastGizmoBaseValue = baseValue;
   }

   public void clear() {
      this.scroll.clear();
      this.gizmo.clear();
      this.scrollDwell.clear();
      this.gizmoDwell.clear();
      this.endFreeScrollSession();
      this.lastGizmoAxis = null;
      this.lastGizmoOperation = null;
      this.lastGizmoSteps = 0;
      this.lastGizmoBaseValue = 0.0;
   }
}
