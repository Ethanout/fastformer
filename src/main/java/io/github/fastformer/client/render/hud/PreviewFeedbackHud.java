package io.github.fastformer.client.render.hud;

import io.github.fastformer.client.render.PreviewFeedbackState;
import io.github.fastformer.client.render.model.AxisFeedback;
import io.github.fastformer.client.render.model.ScrollFeedbackData;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Coordinates transient preview feedback state and its scroll HUD presentation. */
public final class PreviewFeedbackHud {
   private static final int AXIS_SPACING = 14;
   private static final int BOTTOM_OFFSET = 88;

   private final PreviewFeedbackState state = new PreviewFeedbackState();
   private final Function<AxisGizmo.Axis, Integer> axisColor;

   public PreviewFeedbackHud(Function<AxisGizmo.Axis, Integer> axisColor) {
      this.axisColor = axisColor;
   }

   public void clear() {
      this.state.clear();
   }

   /** Records wheel input before the server can publish its replacement snapshot. */
   public void noteScroll(BuildingPreviewPayload building, long now) {
      this.state.scroll().touch(now);
      if (!isActiveFreeScroll(building)) {
         return;
      }
      this.state.beginFreeScrollSession(building.freeScrollOffset());
      this.state.rememberFreeScrollData(coordinates(building.freeScrollOffset()));
      this.state.protectFreeScrollTransition(now);
   }

   public boolean refreshFreeScrollSession(BuildingPreviewPayload building, long now) {
      boolean activeFreeScroll = isActiveFreeScroll(building);
      return this.state.refreshFreeScrollSession(
         building.active(), activeFreeScroll,
         activeFreeScroll ? building.freeScrollOffset() : null, now
      );
   }

   public boolean freeScrollSession() {
      return this.state.freeScrollSession();
   }

   public BlockPos freeScrollOffset() {
      return this.state.freeScrollOffset();
   }

   public ScrollFeedbackData freeScrollData() {
      return this.state.freeScrollData();
   }

   public void rememberFreeScrollData(ScrollFeedbackData data) {
      this.state.rememberFreeScrollData(data);
   }

   public ScrollFeedbackData coordinates(BlockPos value) {
      return new ScrollFeedbackData(List.of(
         axis("X", Integer.toString(value.getX()), AxisGizmo.Axis.X),
         axis("Y", Integer.toString(value.getY()), AxisGizmo.Axis.Y),
         axis("Z", Integer.toString(value.getZ()), AxisGizmo.Axis.Z)
      ), "");
   }

   public ScrollFeedbackData coordinates(Vec3 value) {
      Vec3 clean = GeometryNumbers.cleanZero(value);
      return new ScrollFeedbackData(List.of(
         axis("X", HudValueFormatter.coordinate(clean.x), AxisGizmo.Axis.X),
         axis("Y", HudValueFormatter.coordinate(clean.y), AxisGizmo.Axis.Y),
         axis("Z", HudValueFormatter.coordinate(clean.z), AxisGizmo.Axis.Z)
      ), "");
   }

   public int axisColor(AxisGizmo.Axis axis) {
      return this.axisColor.apply(axis);
   }

   public void renderScrollFeedback(
      GuiGraphics graphics, Minecraft minecraft, ScrollFeedbackData data, boolean persistentFreeScroll, long now
   ) {
      int alpha = feedbackAlpha(minecraft, persistentFreeScroll, now);
      if (alpha <= 0 || data == null) {
         return;
      }
      int y = graphics.guiHeight() - BOTTOM_OFFSET;
      if (!data.axes().isEmpty()) {
         renderAxisFeedback(graphics, minecraft, data.axes(), y, alpha);
      } else if (!data.text().isBlank()) {
         graphics.drawCenteredString(
            minecraft.font, data.text(), graphics.guiWidth() / 2, y, withAlpha(0xFFFFFFFF, alpha)
         );
      }
   }

   public void noteGizmo(AxisGizmo.Axis axis, AxisGizmo.Operation operation, int steps, double baseValue, long now) {
      this.state.noteGizmo(axis, operation, steps, baseValue);
      this.state.gizmo().touch(now);
   }

   public double gizmoDwellMultiplier(Object target, long now) {
      return this.state.gizmoDwell().multiplier(target, now);
   }

   public void clearGizmoDwell() {
      this.state.gizmoDwell().clear();
   }

   /**
    * Reads the gizmo fade for the current frame. The dwell multiplier applies to
    * the hovered handle. A caller that leaves every handle clears the dwell first,
    * which resets only future speed: the pending fade keeps its progress and cannot
    * return to full opacity without a new {@code noteGizmo} input.
    */
   public int gizmoAlpha(long now, Object hoverTarget, double multiplier) {
      if (hoverTarget == null) {
         this.state.gizmoDwell().clear();
      }
      return this.state.gizmo().alpha(now, multiplier);
   }

   public AxisGizmo.Axis lastGizmoAxis() {
      return this.state.lastGizmoAxis();
   }

   public AxisGizmo.Operation lastGizmoOperation() {
      return this.state.lastGizmoOperation();
   }

   public int lastGizmoSteps() {
      return this.state.lastGizmoSteps();
   }

   public double lastGizmoBaseValue() {
      return this.state.lastGizmoBaseValue();
   }

   private static boolean isActiveFreeScroll(BuildingPreviewPayload building) {
      return building.active()
         && building.lineMode() == LineMode.FREE_SCROLL
         && building.points().size() <= 1;
   }

   private AxisFeedback axis(String label, String value, AxisGizmo.Axis axis) {
      return new AxisFeedback(label, value, axisColor(axis));
   }

   private int feedbackAlpha(Minecraft minecraft, boolean persistentFreeScroll, long now) {
      if (persistentFreeScroll) {
         return 255;
      }
      Object target = minecraft.hitResult instanceof BlockHitResult hit ? hit.getBlockPos().immutable() : null;
      return this.state.scroll().alpha(now, this.state.scrollDwell().multiplier(target, now));
   }

   private static void renderAxisFeedback(
      GuiGraphics graphics, Minecraft minecraft, List<AxisFeedback> axes, int y, int alpha
   ) {
      int width = axes.stream()
         .mapToInt(axis -> minecraft.font.width(axis.label() + ":" + axis.value()))
         .sum() + AXIS_SPACING * Math.max(0, axes.size() - 1);
      int x = (graphics.guiWidth() - width) / 2;
      for (AxisFeedback axis : axes) {
         String text = axis.label() + ":" + axis.value();
         graphics.drawString(minecraft.font, text, x, y, withAlpha(axis.color(), alpha), true);
         x += minecraft.font.width(text) + AXIS_SPACING;
      }
   }

   private static int withAlpha(int color, int alpha) {
      return (Math.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
   }
}
