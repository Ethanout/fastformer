package io.github.fastformer.client.operation.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Generic rotation cases that do not need the block registry. Real furnace states live in
 * {@code VoxelRotationSnapshotGameTests}. Axis coverage and translated pivots live in
 * {@code VoxelRotationGeometryTest}.
 *
 * <p>The two-cell +Y90 case is a hard-coded cardinality. A leftover {@code cos(π/2)} residual
 * used to emit three cells. Matching {@code rotatePoint} is not enough: the map must stay two
 * cells at {@code (0,0,0)} and {@code (1,0,0)}.
 */
class VoxelRotationSnapshotTest {
   private static final double QUARTER = Math.PI / 2.0;
   private static final Set<BlockPos> TWO_CELLS_AFTER_POSITIVE_Y_QUARTER =
      Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0));

   @Test
   void genericValuesKeepTheirContentWhileTheCellsMove() {
      Map<BlockPos, String> source = northSouthLine();
      VoxelRotation.RotationResult<String> rotated = VoxelRotation.rotateStage(
         source, new Vec3(0.0, QUARTER, 0.0)
      );

      assertEquals(TWO_CELLS_AFTER_POSITIVE_Y_QUARTER, rotated.values().keySet());
      assertEquals(2, rotated.values().size(), "a +Y90 turn of two cells must stay two cells");
      assertEquals("furnace", rotated.values().get(new BlockPos(1, 0, 0)));
      assertEquals("stone", rotated.values().get(BlockPos.ZERO));
   }

   @Test
   void theComposerKeepsGenericValuesFlat() {
      WorkspaceTransform transform = new WorkspaceTransform(
         Vec3.ZERO, new Vec3(0.0, QUARTER, 0.0), OperationStackRegion.origin()
      );
      Map<BlockPos, String> flat = WorkspacePreviewComposer.resolveValues(northSouthLine(), transform);

      assertEquals(TWO_CELLS_AFTER_POSITIVE_Y_QUARTER, flat.keySet());
      assertEquals(2, flat.size(), "a +Y90 turn of two cells must stay two cells");
      assertEquals("furnace", flat.get(new BlockPos(1, 0, 0)));
      assertEquals("stone", flat.get(BlockPos.ZERO));
   }

   private static Map<BlockPos, String> northSouthLine() {
      Map<BlockPos, String> source = new LinkedHashMap<>();
      source.put(BlockPos.ZERO, "furnace");
      source.put(new BlockPos(0, 0, -1), "stone");
      return source;
   }
}
