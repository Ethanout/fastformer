package io.github.fastformer.client.interaction;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

public final class InteractionComponents {
   public static final ComponentType<InteractionVisibility> VISIBILITY =
      new ComponentType<>("visibility", InteractionVisibility.class);
   public static final ComponentType<Vec3> ANCHOR = new ComponentType<>("anchor", Vec3.class);
   public static final ComponentType<AABB> WORLD_BOUNDS = new ComponentType<>("world_bounds", AABB.class);
   public static final ComponentType<SelectionGizmoInteraction.Frames> GIZMO_FRAMES =
      new ComponentType<>("gizmo_frames", SelectionGizmoInteraction.Frames.class);
   public static final ComponentType<SelectionGizmoInteraction.Group> GROUP_GIZMO =
      new ComponentType<>("group_gizmo", SelectionGizmoInteraction.Group.class);
   public static final ComponentType<PickSphere> PICK_SPHERE = new ComponentType<>("pick_sphere", PickSphere.class);
   public static final ComponentType<PartLabel> PART_LABEL = new ComponentType<>("part_label", PartLabel.class);
   public static final ComponentType<HighlightStyle> HIGHLIGHT = new ComponentType<>("highlight", HighlightStyle.class);
   public static final ComponentType<InteractionTooltip> TOOLTIP = new ComponentType<>("tooltip", InteractionTooltip.class);
   public static final ComponentType<InteractionPressBinding> PRESS_BINDING =
      new ComponentType<>("press_binding", InteractionPressBinding.class);
   public static final ComponentType<SelectionRole> SELECTION_ROLE =
      new ComponentType<>("selection_role", SelectionRole.class);

   private InteractionComponents() { }

   public record PickSphere(double radius) {
      public PickSphere {
         if (!Double.isFinite(radius) || radius <= 0.0) {
            throw new IllegalArgumentException("Pick radius must be finite and positive");
         }
      }
   }

   public enum SelectionRole {
      ORIGINAL_SELECTION, TRANSFORMED_PART, CLIPBOARD_PART;

      public static SelectionRole from(io.github.fastformer.client.operation.model.ClientSelectionPart part) {
         if (part.source() == io.github.fastformer.client.operation.model.ClientSelectionPart.Source.CLIPBOARD) {
            return CLIPBOARD_PART;
         }
         return part.isOriginalSelection() ? ORIGINAL_SELECTION : TRANSFORMED_PART;
      }
   }

   public record PartLabel(int partId) {
      public PartLabel {
         if (partId <= 0) throw new IllegalArgumentException("Part ID must be positive");
      }
   }

   public record Appearance(float scale, int textColor, int backgroundColor) {
      public Appearance {
         if (!Float.isFinite(scale) || scale <= 0.0F) {
            throw new IllegalArgumentException("Label scale must be finite and positive");
         }
      }
   }

   /** Selection and hover are supplied by the owner, never stored as component state. */
   public record HighlightStyle(Appearance normal, Appearance selected, Appearance hovered, float hoverPulseScale) {
      public HighlightStyle {
         Objects.requireNonNull(normal, "normal");
         Objects.requireNonNull(selected, "selected");
         Objects.requireNonNull(hovered, "hovered");
         if (!Float.isFinite(hoverPulseScale) || hoverPulseScale < 0.0F) {
            throw new IllegalArgumentException("Hover pulse scale must be finite and nonnegative");
         }
      }

      public Appearance resolve(boolean selected, boolean hovered, float pulse) {
         if (!hovered) return selected ? this.selected : this.normal;
         float amount = Float.isFinite(pulse) ? Math.clamp(pulse, 0.0F, 1.0F) : 0.0F;
         return new Appearance(this.hovered.scale() + this.hoverPulseScale * amount,
            this.hovered.textColor(), this.hovered.backgroundColor());
      }
   }
}
