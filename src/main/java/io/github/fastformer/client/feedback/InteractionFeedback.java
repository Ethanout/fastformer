package io.github.fastformer.client.feedback;

import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.GeometryNumbers;
import io.github.fastformer.common.quickshape.mode.LineMode;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Transient adjustment feedback owned by one input environment; never draws or queries the client. */
public final class InteractionFeedback {
   private final InteractionFeedbackState state = new InteractionFeedbackState();
   private final Function<Axis, Integer> axisColor;

   public InteractionFeedback() { this(axis -> 0xFF000000 | io.github.fastformer.client.gizmo.GizmoColors.axis(axis)); }

   public InteractionFeedback(Function<Axis, Integer> axisColor) {
      this.axisColor = Objects.requireNonNull(axisColor, "axisColor");
   }

   public void clear() { state.clear(); }

   /** Records input before a server replacement snapshot can arrive. */
   public void noteScroll(BuildingPreviewSnapshot building, long now) {
      state.scroll().touch(now);
      if (!isActiveFreeScroll(building)) return;
      state.beginFreeScrollSession(building.freeScrollOffset());
      state.rememberFreeScrollData(coordinates(building.freeScrollOffset()));
      state.protectFreeScrollTransition(now);
   }

   public boolean refreshFreeScrollSession(BuildingPreviewSnapshot building, long now) {
      boolean active = isActiveFreeScroll(building);
      return state.refreshFreeScrollSession(building.active(), active, active ? building.freeScrollOffset() : null, now);
   }

   public boolean freeScrollSession() { return state.freeScrollSession(); }
   public BlockPos freeScrollOffset() { return state.freeScrollOffset(); }
   public ScrollFeedbackData freeScrollData() { return state.freeScrollData(); }
   public void rememberFreeScrollData(ScrollFeedbackData data) { state.rememberFreeScrollData(data); }

   public ScrollFeedbackData coordinates(BlockPos value) {
      return new ScrollFeedbackData(List.of(
         axis("X", Integer.toString(value.getX()), Axis.X),
         axis("Y", Integer.toString(value.getY()), Axis.Y),
         axis("Z", Integer.toString(value.getZ()), Axis.Z)), "");
   }

   public ScrollFeedbackData coordinates(Vec3 value) {
      Vec3 clean = GeometryNumbers.cleanZero(value);
      return new ScrollFeedbackData(List.of(
         axis("X", HudValueFormatter.coordinate(clean.x), Axis.X),
         axis("Y", HudValueFormatter.coordinate(clean.y), Axis.Y),
         axis("Z", HudValueFormatter.coordinate(clean.z), Axis.Z)), "");
   }

   public int axisColor(Axis axis) { return axisColor.apply(axis); }

   public void noteGizmo(Axis axis, AxisGizmo.Operation operation, int steps, double baseValue) {
      noteGizmo(axis, operation, steps, baseValue, System.nanoTime());
   }

   public void noteGizmo(Axis axis, AxisGizmo.Operation operation, int steps, double baseValue, long now) {
      state.noteGizmo(axis, operation, steps,
         GeometryNumbers.finiteOr(baseValue, operation == AxisGizmo.Operation.SCALE ? 1.0 : 0.0));
      state.gizmo().touch(now);
   }

   public double gizmoDwellMultiplier(Object target, long now) { return state.gizmoDwell().multiplier(target, now); }

   /** Leaving the handle changes future fade speed, but never restores already faded opacity. */
   public int gizmoAlpha(long now, Object hoverTarget, double multiplier) {
      if (hoverTarget == null) state.gizmoDwell().clear();
      return state.gizmo().alpha(now, multiplier);
   }

   public int scrollAlpha(boolean persistent, Object target, long now) {
      return persistent ? 255 : state.scroll().alpha(now, state.scrollDwell().multiplier(target, now));
   }

   public Axis lastGizmoAxis() { return state.lastGizmoAxis(); }
   public AxisGizmo.Operation lastGizmoOperation() { return state.lastGizmoOperation(); }
   public int lastGizmoSteps() { return state.lastGizmoSteps(); }
   public double lastGizmoBaseValue() { return state.lastGizmoBaseValue(); }

   private static boolean isActiveFreeScroll(BuildingPreviewSnapshot building) {
      return building.active() && building.lineMode() == LineMode.FREE_SCROLL && building.points().size() <= 1;
   }

   private AxisFeedback axis(String label, String value, Axis axis) {
      return new AxisFeedback(label, value, axisColor(axis));
   }
}
