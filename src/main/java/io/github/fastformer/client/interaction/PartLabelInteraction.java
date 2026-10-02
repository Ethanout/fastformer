package io.github.fastformer.client.interaction;

import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.render.WorkspacePartLabelHint;
import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Adapts selection label semantics to shared geometry and presentation components. */
public final class PartLabelInteraction {
   private static InteractionComponents.HighlightStyle style() {
      return new InteractionComponents.HighlightStyle(
         new InteractionComponents.Appearance(PreviewStyle.LABEL_SCALE, GeometryPalette.paperMuted().argb(), GeometryPalette.paper().argb(80)),
         new InteractionComponents.Appearance(PreviewStyle.LABEL_SCALE, GeometryPalette.paperInk().argb(), GeometryPalette.paper().argb(210)),
         new InteractionComponents.Appearance(PreviewStyle.LABEL_SCALE, GeometryPalette.paperInk().argb(), GeometryPalette.paper().argb(230)),
         0.0F
      );
   }

   private PartLabelInteraction() { }

   public static InteractionObject create(UUID session, long interactionId, int partId, AABB bounds) {
      return create(session, interactionId, partId, bounds, InteractionComponents.SelectionRole.TRANSFORMED_PART);
   }

   public static InteractionObject create(UUID session, long interactionId, int partId, AABB bounds,
      InteractionComponents.SelectionRole role) {
      return InteractionObject.builder(new InteractionObject.Id(session, "part_label", interactionId))
         .with(InteractionComponents.ANCHOR, bounds.getCenter().add(0.0, 0.22, 0.0))
         .with(InteractionComponents.PICK_SPHERE, new InteractionComponents.PickSphere(0.22))
         .with(InteractionComponents.PART_LABEL, new InteractionComponents.PartLabel(partId))
         .with(InteractionComponents.SELECTION_ROLE, role)
         .with(InteractionComponents.TOOLTIP, InteractionTooltip.SELECTION)
         .with(InteractionComponents.PRESS_BINDING, InteractionPressBinding.SELECT)
         .with(InteractionComponents.HIGHLIGHT, style())
         .build();
   }

   public static Presentation present(InteractionObject object, Context context) {
      int partId = object.require(InteractionComponents.PART_LABEL).partId();
      return new Presentation(
         object.require(InteractionComponents.ANCHOR),
         WorkspacePartLabelHint.text(partId, context.selected(), context.locked(), context.control(), context.intent()),
         style().resolve(context.selected(), context.hovered(), context.pulse()),
         object.require(InteractionComponents.SELECTION_ROLE)
      );
   }

   public record Context(
      boolean selected, boolean hovered, boolean locked, boolean control, OperationInteractionIntent intent, float pulse
   ) { }

   public record Presentation(Vec3 anchor, Component text, InteractionComponents.Appearance appearance,
      InteractionComponents.SelectionRole role) { }
}
