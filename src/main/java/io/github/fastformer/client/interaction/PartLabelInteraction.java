package io.github.fastformer.client.interaction;

import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.client.render.WorkspacePartLabelHint;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Adapts selection label semantics to shared geometry and presentation components. */
public final class PartLabelInteraction {
   private static final InteractionComponents.HighlightStyle STYLE = new InteractionComponents.HighlightStyle(
      new InteractionComponents.Appearance(0.025F, 0xFFB9D7E8, 0x50000000),
      new InteractionComponents.Appearance(0.025F * 1.14F, 0xFFFFD66B, 0xA0603D00),
      new InteractionComponents.Appearance(0.025F * 1.28F, 0xFF83F5FF, 0xC0004050),
      0.025F * 0.08F
   );

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
         .with(InteractionComponents.HIGHLIGHT, STYLE)
         .build();
   }

   public static Presentation present(InteractionObject object, Context context) {
      int partId = object.require(InteractionComponents.PART_LABEL).partId();
      return new Presentation(
         object.require(InteractionComponents.ANCHOR),
         WorkspacePartLabelHint.text(partId, context.selected(), context.locked(), context.control(), context.intent()),
         object.require(InteractionComponents.HIGHLIGHT).resolve(context.selected(), context.hovered(), context.pulse()),
         object.require(InteractionComponents.SELECTION_ROLE)
      );
   }

   public record Context(
      boolean selected, boolean hovered, boolean locked, boolean control, OperationInteractionIntent intent, float pulse
   ) { }

   public record Presentation(Vec3 anchor, Component text, InteractionComponents.Appearance appearance,
      InteractionComponents.SelectionRole role) { }
}
