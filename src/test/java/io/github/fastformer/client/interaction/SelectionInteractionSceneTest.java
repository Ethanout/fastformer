package io.github.fastformer.client.interaction;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SelectionInteractionSceneTest {
   @Test
   void readingASceneDoesNotPublishUnfinishedEdits() {
      var session = new ClientSelectionSession();
      var empty = session.interactionScene();
      session.workspace().addParts(List.of(cuboid(0)));
      assertSame(empty, session.interactionScene());
      session.publishInteractionScene();
      assertEquals(1, session.interactionScene().parts().size());
      assertTrue(empty.parts().isEmpty());
      assertThrows(UnsupportedOperationException.class, () -> session.interactionScene().parts().clear());
   }

   @Test
   void sceneObjectsPublishTheSelectionRoleAsAComponent() {
      var session = new ClientSelectionSession();
      session.workspace().addParts(List.of(cuboid(0)));
      session.publishInteractionScene();
      var original = session.interactionScene().parts().get(1);
      assertEquals(InteractionComponents.SelectionRole.ORIGINAL_SELECTION,
         original.frame().require(InteractionComponents.SELECTION_ROLE));
      assertEquals(InteractionComponents.SelectionRole.ORIGINAL_SELECTION,
         original.label().require(InteractionComponents.SELECTION_ROLE));
      assertEquals(InteractionComponents.SelectionRole.ORIGINAL_SELECTION,
         original.gizmo().require(InteractionComponents.SELECTION_ROLE));
      assertTrue(session.workspace().beginEdit());
      session.workspace().updatePart(session.workspace().part(1).orElseThrow()
         .withTranslation(new BlockPos(4, 0, 0)));
      assertTrue(session.workspace().finishEdit());
      session.publishInteractionScene();
      var transformed = session.interactionScene().parts().get(1);
      assertEquals(InteractionComponents.SelectionRole.TRANSFORMED_PART,
         transformed.frame().require(InteractionComponents.SELECTION_ROLE));
      assertEquals(InteractionComponents.SelectionRole.TRANSFORMED_PART,
         transformed.label().require(InteractionComponents.SELECTION_ROLE));
      assertEquals(InteractionComponents.SelectionRole.TRANSFORMED_PART,
         transformed.gizmo().require(InteractionComponents.SELECTION_ROLE));
      assertTrue(session.workspace().beginEdit());
      session.workspace().updatePart(session.workspace().part(1).orElseThrow().withTranslation(BlockPos.ZERO));
      assertTrue(session.workspace().finishEdit());
      session.publishInteractionScene();
      var restored = session.interactionScene().parts().get(1);
      for (var object : List.of(restored.frame(), restored.label(), restored.gizmo())) {
         assertEquals(InteractionComponents.SelectionRole.ORIGINAL_SELECTION,
            object.require(InteractionComponents.SELECTION_ROLE));
      }
      assertEquals(original.frame().id(), restored.frame().id());
      assertEquals(original.label().id(), restored.label().id());
      assertEquals(original.gizmo().id(), restored.gizmo().id());
      assertEquals(original.bounds(), restored.bounds());
      assertTrue(restored.source().canAdjustGeometry());
      assertEquals(InteractionComponents.SelectionRole.TRANSFORMED_PART,
         transformed.label().require(InteractionComponents.SELECTION_ROLE));
   }

   @Test
   void stableSceneIsReusedAndSelectionOnlyUpdatesReusePartObjects() {
      var session = populated();
      var first = session.interactionScene();
      session.publishInteractionScene();
      assertSame(first, session.interactionScene());
      session.workspace().selectOnly(1);
      session.publishInteractionScene();
      var selected = session.interactionScene();
      assertNotSame(first, selected);
      assertNotNull(first.groupGizmo());
      assertNull(selected.groupGizmo());
      assertSame(first.parts().get(1), selected.parts().get(1));
      session.workspace().setLocked(true);
      session.publishInteractionScene();
      assertSame(selected, session.interactionScene());
   }

   @Test
   void movingOnePartReplacesOnlyItsGeometryAndRetainsTheOldSnapshot() {
      var session = populated();
      var before = session.interactionScene();
      var workspace = session.workspace();
      assertTrue(workspace.beginEdit());
      workspace.updatePart(workspace.part(1).orElseThrow().withTranslation(new BlockPos(7, 0, 0)));
      assertTrue(workspace.finishEdit());
      session.publishInteractionScene();
      var after = session.interactionScene();
      assertNotSame(before, after);
      assertSame(before.parts().get(2), after.parts().get(2));
      assertEquals(before.bounds(1).move(7, 0, 0), after.bounds(1));
      assertEquals(before.parts().get(1).frame().id(), after.parts().get(1).frame().id());
      assertEquals(before.parts().get(1).gizmo().id(), after.parts().get(1).gizmo().id());
      assertNotEquals(after.parts().get(1).gizmo().id(), after.parts().get(1).frame().id());
      assertSame(after.bounds(1), after.parts().get(1).frame().require(InteractionComponents.WORLD_BOUNDS));
      var oldLabel = before.parts().get(1).label();
      var newLabel = after.parts().get(1).label();
      assertEquals(oldLabel.id(), newLabel.id());
      assertEquals(oldLabel.require(InteractionComponents.ANCHOR).add(7, 0, 0),
         newLabel.require(InteractionComponents.ANCHOR));
      assertEquals(cuboid(0).selection().bounds(), before.bounds(1));
   }

   @Test
   void removalAndReusedDisplayNumberCannotReuseTheOldObject() {
      var session = populated();
      var original = session.interactionScene().parts().get(2).label();
      session.workspace().selectOnly(2);
      assertTrue(session.workspace().removeSelectedParts());
      session.publishInteractionScene();
      assertNull(session.interactionScene().bounds(2));
      session.workspace().addParts(List.of(cuboid(20)));
      session.publishInteractionScene();
      assertNotEquals(original.id(), session.interactionScene().parts().get(2).label().id());
   }

   @Test
   void cleanupImmediatelyPublishesAnEmptySceneForANewOwner() {
      var session = populated();
      var before = session.interactionScene();
      session.clearLiveInteraction();
      assertTrue(session.interactionScene().parts().isEmpty());
      assertNotEquals(before.owner(), session.interactionScene().owner());
      assertEquals(2, before.parts().size());
   }

   @Test
   void emptyNonCuboidKeepsItsFullSelectionEnvelopeAndLabel() {
      var selection = OperationSelectionVolume.create(OperationSelectionMode.CONVEX_HULL,
         List.of(BlockPos.ZERO, new BlockPos(8, 0, 0), new BlockPos(0, 0, 5)),
         BlockPos.ZERO, BlockPos.ZERO, 0);
      var session = new ClientSelectionSession();
      session.workspace().addParts(List.of(new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false)));
      session.publishInteractionScene();
      var part = session.interactionScene().parts().get(1);
      assertEquals(selection.bounds(), part.bounds());
      assertEquals(part.bounds().getCenter().add(0, 0.22, 0), part.label().require(InteractionComponents.ANCHOR));
   }

   @Test
   void controllerPublishesMoveAndUndoBeforeTheNextTick() {
      ClientOperationController.clearWorkspace();
      try {
         ClientOperationController.workspace().addParts(List.of(cuboid(0)));
         assertTrue(ClientOperationController.moveSelected(new BlockPos(4, 0, 0)));
         var moved = ClientOperationController.interactionScene();
         assertEquals(cuboid(0).selection().bounds().move(4, 0, 0), moved.bounds(1));
         assertTrue(ClientOperationController.undo());
         assertEquals(cuboid(0).selection().bounds(), ClientOperationController.interactionScene().bounds(1));
         assertEquals(cuboid(0).selection().bounds().move(4, 0, 0), moved.bounds(1));
      } finally {
         ClientOperationController.clearWorkspace();
      }
      assertTrue(ClientOperationController.interactionScene().parts().isEmpty());
   }

   @Test
   void selectionCommandsPublishTheGroupWithoutMutatingTheOldSnapshot() {
      ClientOperationController.clearWorkspace();
      try {
         ClientOperationController.workspace().addParts(List.of(cuboid(0), cuboid(10)));
         ClientOperationController.selectAllWorkspaceParts();
         var before = ClientOperationController.interactionScene();
         var group = before.groupGizmo();
         assertNotNull(group);
         assertEquals(2, group.require(InteractionComponents.GROUP_GIZMO).members().size());
         ClientOperationController.selectAllWorkspaceParts();
         assertSame(before, ClientOperationController.interactionScene());
         ClientOperationController.selectWorkspacePart(1, false);
         assertNull(ClientOperationController.interactionScene().groupGizmo());
         assertSame(group, before.groupGizmo());
         ClientOperationController.selectWorkspacePart(2, true);
         var restored = ClientOperationController.interactionScene().groupGizmo();
         assertEquals(group.id(), restored.id());
         assertEquals(group.require(InteractionComponents.WORLD_BOUNDS), restored.require(InteractionComponents.WORLD_BOUNDS));
         ClientOperationController.clearWorkspace();
         assertNull(ClientOperationController.interactionScene().groupGizmo());
         assertNotEquals(before.owner(), ClientOperationController.interactionScene().owner());
      } finally {
         ClientOperationController.clearWorkspace();
      }
   }

   @Test
   void aPartWithoutAnEnvelopeDoesNotCreateAnInvalidLabel() {
      var session = new ClientSelectionSession();
      session.workspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      session.publishInteractionScene();
      var snapshot = session.interactionScene();
      assertNull(snapshot.parts().get(1).bounds());
      assertNull(snapshot.parts().get(1).label());
      assertNull(snapshot.parts().get(1).frame());
      assertNull(snapshot.parts().get(1).gizmo());
      session.publishInteractionScene();
      assertSame(snapshot, session.interactionScene());
   }

   private static ClientSelectionSession populated() {
      var session = new ClientSelectionSession();
      session.workspace().addParts(List.of(cuboid(0), cuboid(10)));
      session.publishInteractionScene();
      return session;
   }

   @Test
   void foreignGestureCannotFinishCancelOrPublishTheCurrentEdit() {
      var foreign = populated();
      foreign.workspace().beginEdit();
      var token = foreign.workspace().activeEditToken();
      ClientOperationController.clearWorkspace();
      try {
         var workspace = ClientOperationController.workspace();
         workspace.addParts(List.of(cuboid(0)));
         workspace.beginEdit();
         var current = workspace.activeEditToken();
         var unpublished = ClientOperationController.interactionScene();
         ClientOperationController.cancelTransformGesture(token);
         assertFalse(ClientOperationController.finishTransformGesture(token));
         assertTrue(workspace.ownsEdit(current));
         assertSame(unpublished, ClientOperationController.interactionScene());
      } finally {
         ClientOperationController.clearWorkspace();
      }
   }

   private static ClientSelectionPart cuboid(int x) {
      var selection = OperationSelectionVolume.cuboid(new BlockPos(x, 0, 0), new BlockPos(x + 3, 2, 4),
         BlockPos.ZERO, BlockPos.ZERO);
      return new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false);
   }
}
