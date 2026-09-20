package io.github.fastformer.client.operation.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import sun.misc.Unsafe;
import org.junit.jupiter.api.Test;

class ClientSelectionPartTest {
   @Test
   void resizingBeforeTransformMovesTheEditableBaseline() {
      ClientSelectionPart part = part(new AABB(0, 0, 0, 2, 2, 2));
      OperationSelectionVolume resized = volume(new AABB(0, 0, 0, 4, 2, 2));

      ClientSelectionPart updated = part.withSelection(resized).withBlocks(Map.of());

      assertTrue(updated.editability() == ClientSelectionPart.Editability.FREE);
      assertTrue(updated.baseline() == null);
      assertTrue(updated.isOriginalSelection(), "editing the selection bounds must not create a part");
      assertTrue(updated.selection().bounds().equals(resized.bounds()));
   }

   @Test
   void firstLinearTransformFreezesTheCurrentSelectionAsBaseline() {
      ClientSelectionPart resized = part(new AABB(0, 0, 0, 2, 2, 2))
         .withSelection(volume(new AABB(-1, 0, 0, 4, 2, 2)));

      ClientSelectionPart moved = resized.withTranslation(new Vec3(3, 0, 0));

      assertTrue(moved.editability() == ClientSelectionPart.Editability.LOCKED);
      assertTrue(moved.baseline() != null);
      assertTrue(moved.baseline().selection().bounds().equals(resized.selection().bounds()));
   }

   @Test
   void returningToTheFrozenBaselineMakesTheSelectionAdjustableAgain() {
      ClientSelectionPart moved = part(new AABB(0, 0, 0, 2, 2, 2))
         .withTranslation(new Vec3(3, 0, 0));

      ClientSelectionPart returned = moved.withTranslation(Vec3.ZERO);

      assertTrue(returned.editability() == ClientSelectionPart.Editability.FREE);
      assertTrue(returned.baseline() == null);
      assertFalse(returned.transform().hasEffect());
   }

   @Test
   void lockedPartRejectsGeometryEdits() {
      ClientSelectionPart moved = part(new AABB(0, 0, 0, 2, 2, 2))
         .withTranslation(new Vec3(3, 0, 0));

      ClientSelectionPart attempted = moved.withSelection(volume(new AABB(0, 0, 0, 8, 2, 2)));

      assertTrue(attempted == moved);
      assertTrue(attempted.editability() == ClientSelectionPart.Editability.LOCKED);
   }

   private static ClientSelectionPart part(AABB bounds) {
      return new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, volume(bounds),
         Map.of(BlockPos.ZERO, snapshot()),
         WorkspaceTransform.IDENTITY, false
      );
   }

   @Test
   void symmetricRotationReturnsToEditableSelectionWithoutIdentityParameters() {
      var original = part(new AABB(0, 0, 0, 1, 1, 1));
      assertTrue(original.isOriginalSelection());
      var moved = original.withTranslation(new Vec3(4, 0, 0));
      assertFalse(moved.isOriginalSelection());
      var rotated = moved.withTransform(moved.transform().withRotation(new Vec3(Math.PI, 0, 0)));
      var returned = rotated.withTranslation(Vec3.ZERO);
      assertEquals(ClientSelectionPart.Editability.FREE, returned.editability());
      assertFalse(returned.masksSourceBlocks());
      assertEquals(original.transform(), returned.transform());
      assertEquals(original.blocks(), returned.blocks());
      assertTrue(returned.canAdjustGeometry());
   }

   @Test
   void equalMapHashesDoNotMakeDifferentPositionsEquivalent() {
      var original = part(new AABB(0, 0, 0, 1, 1, 1));
      var position = new BlockPos(31, -1, 0);
      assertEquals(BlockPos.ZERO.hashCode(), position.hashCode());
      var moved = original.withTranslation(position);
      assertEquals(ClientSelectionPart.Editability.LOCKED, moved.editability());
      assertTrue(moved.masksSourceBlocks());
   }

   @Test
   void equivalentResultRestoresOriginalStorageBeforeTheNextTransform() {
      var original = part(new AABB(0, 0, 0, 1, 1, 1));
      var moved = original.withTranslation(new Vec3(4, 0, 0));
      var shiftedStorage = moved.withBlocks(Map.of(new BlockPos(2, 0, 0), original.blocks().get(BlockPos.ZERO)));

      var restored = shiftedStorage.withTranslation(new Vec3(-2, 0, 0));

      assertEquals(ClientSelectionPart.Editability.FREE, restored.editability());
      assertEquals(original.blocks(), restored.blocks());
      assertEquals(original.sourceSnapshot(), restored.sourceSnapshot());
      assertEquals(original.transform(), restored.transform());
      var movedAgain = restored.withTranslation(new Vec3(3, 0, 0));
      assertEquals(ClientSelectionPart.Editability.LOCKED, movedAgain.editability());
      assertEquals(original.blocks(), movedAgain.baseline().sourceSnapshot());
      assertEquals(original.blocks(), movedAgain.withTranslation(Vec3.ZERO).blocks());
   }

   @Test
   void changedContentsCannotUnlockJustBecauseTransformReturnsToIdentity() {
      var original = part(new AABB(0, 0, 0, 1, 1, 1));
      var moved = original.withTranslation(new Vec3(4, 0, 0));
      var changed = moved.withBlocks(Map.of(new BlockPos(1, 0, 0), original.blocks().get(BlockPos.ZERO)));

      var returned = changed.withTranslation(Vec3.ZERO);

      assertEquals(ClientSelectionPart.Editability.LOCKED, returned.editability());
      assertEquals(original.sourceSnapshot(), returned.baseline().sourceSnapshot());
      assertEquals(changed.blocks(), returned.blocks());
      assertTrue(returned.transformed());
      assertTrue(returned.masksSourceBlocks());
   }

   @Test
   void emptySelectionRestoresOnlyWhenItsOuterFrameAlsoMatches() {
      var original = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD,
         volume(new AABB(0, 0, 0, 2, 2, 2)), Map.of(), WorkspaceTransform.IDENTITY, false
      );
      var moved = original.withTranslation(new Vec3(3, 0, 0));
      var sameFrame = moved.withTranslation(Vec3.ZERO);
      assertTrue(sameFrame.isOriginalSelection(), "matching empty shape did not restore selection");

      var differentFrame = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD,
         volume(new AABB(0, 0, 0, 3, 2, 2)), Map.of(),
         moved.transform(), false, Map.of(),
         new io.github.fastformer.client.operation.selection.SelectionBaseline(
            original.selection(), WorkspaceTransform.IDENTITY, Map.of()),
         ClientSelectionPart.Editability.LOCKED
      ).withTranslation(Vec3.ZERO);
      assertFalse(differentFrame.isOriginalSelection(), "empty blocks ignored a changed outer frame");
   }

   @Test
   void restoringContentsUnlocksWithoutAnotherTransformEvent() {
      var original = part(new AABB(0, 0, 0, 1, 1, 1));
      var changed = original.withTranslation(new Vec3(4, 0, 0))
         .withBlocks(Map.of(new BlockPos(1, 0, 0), original.blocks().get(BlockPos.ZERO)))
         .withTranslation(Vec3.ZERO);
      assertFalse(changed.isOriginalSelection());

      var restored = changed.withBlocks(original.blocks());

      assertEquals(original, restored);
      assertTrue(restored.isOriginalSelection());
      assertTrue(restored.canAdjustGeometry());
      assertFalse(restored.masksSourceBlocks());
   }

   @Test
   void contentUpdateComparesWorldPositionsAndNormalizesRestoredStorage() {
      var original = part(new AABB(0, 0, 0, 1, 1, 1));
      var moved = original.withTranslation(new Vec3(4, 0, 0));

      var restored = moved.withBlocks(Map.of(new BlockPos(-4, 0, 0), original.blocks().get(BlockPos.ZERO)));

      assertEquals(original, restored);
      assertEquals(original.blocks(), restored.withTranslation(new Vec3(2, 0, 0)).baseline().sourceSnapshot());
   }

   private static ClientBlockSnapshot snapshot() {
      try {
         var field = Unsafe.class.getDeclaredField("theUnsafe");
         field.setAccessible(true);
         return (ClientBlockSnapshot)((Unsafe)field.get(null)).allocateInstance(ClientBlockSnapshot.class);
      } catch (ReflectiveOperationException exception) {
         throw new AssertionError(exception);
      }
   }

   private static OperationSelectionVolume volume(AABB bounds) {
      return new OperationSelectionVolume(OperationSelectionMode.CUBOID, bounds, null, List.of(), 0, null, null);
   }
}
