package io.github.fastformer.client.operation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ClientSelectionPartTest {
   @Test
   void resizingBeforeTransformMovesTheEditableBaseline() {
      ClientSelectionPart part = part(new AABB(0, 0, 0, 2, 2, 2));
      OperationSelectionVolume resized = volume(new AABB(0, 0, 0, 4, 2, 2));

      ClientSelectionPart updated = part.withSelection(resized).withBlocks(Map.of());

      assertTrue(updated.editability() == ClientSelectionPart.Editability.FREE);
      assertTrue(updated.baseline() == null);
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
         1, ClientSelectionPart.Source.WORLD, volume(bounds), Map.of(), WorkspaceTransform.IDENTITY, false
      );
   }

   private static OperationSelectionVolume volume(AABB bounds) {
      return new OperationSelectionVolume(OperationSelectionMode.CUBOID, bounds, null, List.of(), 0, null, null);
   }
}
