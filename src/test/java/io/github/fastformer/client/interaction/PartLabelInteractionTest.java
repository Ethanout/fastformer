package io.github.fastformer.client.interaction;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.render.WorkspacePartLabelHint;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PartLabelInteractionTest {
   @Test
   void renderingAndPickingUseTheSameWorldAnchorAtFarCoordinates() {
      var object = label(new AABB(29_999_900, 80, -29_999_900, 29_999_904, 84, -29_999_894));
      Vec3 anchor = object.require(InteractionComponents.ANCHOR);
      var presentation = PartLabelInteraction.present(object, context(false, false, false));
      assertEquals(anchor, presentation.anchor());
      assertEquals(10.0, InteractionGeometry.hitDistance(object, anchor.add(0, 0, 10), new Vec3(0, 0, -1), 12).orElseThrow(), 1.0E-9);
   }

   @Test
   void hoverChangesAppearanceButCannotMoveThePickAnchor() {
      var object = label(new AABB(BlockPos.ZERO));
      var normal = PartLabelInteraction.present(object, context(false, false, false));
      var hovered = PartLabelInteraction.present(object, context(false, true, false));
      assertEquals(normal.anchor(), hovered.anchor());
      assertEquals(0.025F, normal.appearance().scale());
      assertEquals(0.025F * 1.36F, hovered.appearance().scale(), 1.0E-6F);
      assertEquals(0xFF83F5FF, hovered.appearance().textColor());
   }

   @Test
   void ownerSelectionAndLockStillControlTheExistingHintSemantics() {
      var object = label(new AABB(BlockPos.ZERO));
      var selected = PartLabelInteraction.present(object, context(true, true, false));
      var unselected = PartLabelInteraction.present(object, context(false, true, false));
      var locked = PartLabelInteraction.present(object, context(true, true, true));
      assertEquals(WorkspacePartLabelHint.DESELECT_KEY, key(selected));
      assertEquals(WorkspacePartLabelHint.APPEND_KEY, key(unselected));
      assertEquals(WorkspacePartLabelHint.SELECTED_KEY, key(locked));
      assertEquals(selected.anchor(), locked.anchor());
   }

   @Test
   void pickToleranceAndReachRejectMissesWithoutChangingTheObject() {
      var object = label(new AABB(BlockPos.ZERO));
      Vec3 anchor = object.require(InteractionComponents.ANCHOR);
      assertTrue(InteractionGeometry.hitDistance(object, anchor.add(0.23, 0, 5), new Vec3(0, 0, -1), 10).isEmpty());
      assertTrue(InteractionGeometry.hitDistance(object, anchor.add(0, 0, 5), new Vec3(0, 0, -1), 4).isEmpty());
      assertTrue(InteractionGeometry.hitDistance(object, anchor.add(0, 0, 5), new Vec3(0, 0, 1), 10).isEmpty());
      assertEquals(anchor, object.require(InteractionComponents.ANCHOR));
   }

   @Test
   void nonUnitViewUsesWorldDistanceAndInvalidRaysDoNotHit() {
      var object = label(new AABB(BlockPos.ZERO));
      Vec3 eye = object.require(InteractionComponents.ANCHOR).add(0, 0, 5);
      assertEquals(5.0, InteractionGeometry.hitDistance(object, eye, new Vec3(0, 0, -2), 10).orElseThrow(), 1.0E-9);
      assertTrue(InteractionGeometry.hitDistance(object, eye, Vec3.ZERO, 10).isEmpty());
      assertTrue(InteractionGeometry.hitDistance(object, eye, new Vec3(Double.NaN, 0, 0), 10).isEmpty());
      assertTrue(InteractionGeometry.hitDistance(object, eye, new Vec3(0, 0, -1), Double.NaN).isEmpty());
   }

   @Test
   void objectIdentitySurvivesTransformButNotEnvironmentCleanup() {
      var session = new ClientSelectionSession();
      var before = PartLabelInteraction.create(session.interactionOwnerId(), 1L, 1, new AABB(BlockPos.ZERO));
      session.addDraftPoint(BlockPos.ZERO);
      session.clearDraft();
      var moved = PartLabelInteraction.create(session.interactionOwnerId(), 1L, 1, new AABB(new BlockPos(10, 0, 0)));
      assertEquals(before.id(), moved.id());
      assertNotEquals(before.require(InteractionComponents.ANCHOR), moved.require(InteractionComponents.ANCHOR));

      session.clearLiveInteraction();
      var after = PartLabelInteraction.create(session.interactionOwnerId(), 1L, 1, new AABB(BlockPos.ZERO));
      assertNotEquals(before.id(), after.id());
   }

   @Test
   void componentDimensionsRejectInvalidValues() {
      assertThrows(IllegalArgumentException.class, () -> new InteractionComponents.PickSphere(0));
      assertThrows(IllegalArgumentException.class, () -> new InteractionComponents.PickSphere(Double.NaN));
      assertThrows(IllegalArgumentException.class, () -> new InteractionComponents.Appearance(Float.NaN, 0, 0));
      assertThrows(IllegalArgumentException.class, () -> new InteractionComponents.PartLabel(0));
   }

   @Test
   void reusedDisplayNumberDoesNotReuseTheLabelObjectIdentity() {
      var session = new ClientSelectionSession();
      var workspace = session.workspace();
      var part = io.github.fastformer.client.operation.model.ClientSelectionPart.empty(
         io.github.fastformer.client.operation.model.ClientSelectionPart.Source.WORLD);
      workspace.addParts(java.util.List.of(part));
      var before = PartLabelInteraction.create(
         session.interactionOwnerId(), workspace.interactionId(1), 1, new AABB(BlockPos.ZERO));
      assertTrue(workspace.removeSelectedParts());
      workspace.addParts(java.util.List.of(part));
      var after = PartLabelInteraction.create(
         session.interactionOwnerId(), workspace.interactionId(1), 1, new AABB(BlockPos.ZERO));
      assertNotEquals(before.id(), after.id());
      assertEquals(before.require(InteractionComponents.PART_LABEL), after.require(InteractionComponents.PART_LABEL));
   }

   private static InteractionObject label(AABB bounds) {
      return PartLabelInteraction.create(UUID.randomUUID(), 1L, 1, bounds);
   }

   private static PartLabelInteraction.Context context(boolean selected, boolean hovered, boolean locked) {
      return new PartLabelInteraction.Context(selected, hovered, locked, true, new OperationInteractionIntent.Part(1, 5), 1.0F);
   }

   private static String key(PartLabelInteraction.Presentation presentation) {
      return ((TranslatableContents) presentation.text().getContents()).getKey();
   }
}
