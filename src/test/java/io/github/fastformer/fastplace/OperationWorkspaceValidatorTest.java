package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.ClientBlockSnapshot;
import io.github.fastformer.client.operation.ClientSelectionPart;
import io.github.fastformer.client.operation.WorkspaceTransform;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class OperationWorkspaceValidatorTest {
   @Test
   void rejectsMoreThanTenPartsBeforeReadingAnyWorldState() {
      List<OperationWorkspacePlan.Part> parts = new ArrayList<>();
      for (int id = 1; id <= 11; id++) {
         parts.add(emptyPart(id));
      }
      boolean[] read = {false};

      var result = OperationWorkspaceValidator.validate(
         new OperationWorkspacePlan(parts), ignored -> {
            read[0] = true;
            return Optional.empty();
         }, 100
      );

      assertFalse(result.success());
      assertFalse(read[0]);
      assertTrue(result.writes().isEmpty());
   }

   @Test
   void rejectsDuplicateIdsBeforeReadingAnyWorldState() {
      boolean[] read = {false};
      var result = OperationWorkspaceValidator.validate(
         new OperationWorkspacePlan(List.of(emptyPart(1), emptyPart(1))),
         ignored -> {
            read[0] = true;
            return Optional.empty();
         }, 100
      );

      assertFalse(result.success());
      assertFalse(read[0]);
   }

   @Test
   void rejectsUnboundedRepeatMetadataBeforeVoxelExpansion() {
      WorkspaceTransform hostile = new WorkspaceTransform(
         Vec3.ZERO,
         Vec3.ZERO,
         new OperationStackRegion(BlockPos.ZERO, new BlockPos(1_000_000, 0, 0)),
         BlockPos.ZERO
      );

      assertFalse(OperationWorkspaceValidator.validTransform(hostile));
   }

   @Test
   void doesNotReadLiveWorldWhenSubmittingSavedWorldSnapshot() throws ReflectiveOperationException {
      BlockPos source = new BlockPos(4, 5, 6);
      ClientBlockSnapshot clientSnapshot = inertSnapshot();
      OperationWorkspacePlan.Part part = new OperationWorkspacePlan.Part(
         1,
         ClientSelectionPart.Source.WORLD,
         Map.of(source, clientSnapshot),
         WorkspaceTransform.IDENTITY,
         true
      );
      boolean[] read = {false};

      var result = OperationWorkspaceValidator.validate(
         new OperationWorkspacePlan(List.of(part)),
         ignored -> {
            read[0] = true;
            return Optional.empty();
         },
         100
      );

      assertTrue(result.success());
      assertFalse(read[0]);
      assertTrue(result.clears().contains(source));
   }

   private static ClientBlockSnapshot inertSnapshot() throws ReflectiveOperationException {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      return (ClientBlockSnapshot)((Unsafe)field.get(null)).allocateInstance(ClientBlockSnapshot.class);
   }

   private static OperationWorkspacePlan.Part emptyPart(int id) {
      return new OperationWorkspacePlan.Part(
         id, ClientSelectionPart.Source.CLIPBOARD, Map.of(), WorkspaceTransform.IDENTITY, false
      );
   }
}
