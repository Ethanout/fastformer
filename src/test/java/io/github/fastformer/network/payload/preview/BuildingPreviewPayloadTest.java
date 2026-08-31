package io.github.fastformer.network.payload.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.FaceMode;
import io.github.fastformer.fastplace.FaceRasterizationMode;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.LineMode;
import io.github.fastformer.fastplace.PointMode;
import io.github.fastformer.fastplace.PolygonVolumeShape;
import io.github.fastformer.fastplace.PlacementContextSnapshot;
import io.github.fastformer.fastplace.RaycastPlacement;
import io.github.fastformer.fastplace.VolumeMode;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BuildingPreviewPayloadTest {
   @Test
   void confirmedFaceTieBiasSurvivesCodecAndModesSnapshot() {
      PlacementContextSnapshot placement = new PlacementContextSnapshot(
         new BlockPos(4, 5, 6),
         new Vec3(4.25, 5.75, 6.5),
         Direction.EAST,
         false,
         false,
         37.5F,
         Direction.WEST,
         Direction.UP,
         List.of(Direction.UP, Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.DOWN),
         true
      );
      BuildingPreviewPayload payload = new BuildingPreviewPayload(
         true,
         false,
         true,
         true,
         false,
         false,
         PolygonVolumeShape.EXTRUDE,
         List.of(BlockPos.ZERO, new BlockPos(2, 1, 0), new BlockPos(0, 0, 2)),
         0,
         BlockPos.ZERO,
         Vec3.ZERO,
         Vec3.ZERO,
         BlockPos.ZERO,
         0.0,
         PointMode.RAYCAST,
         RaycastPlacement.EMBEDDED,
         LineMode.AXIS,
         FaceMode.COORDINATE_PLANE,
         VolumeMode.PERPENDICULAR_TO_FACE,
         FillMode.SOLID,
         LineTieBias.OPPOSITE,
         FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL,
         placement
      );

      FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
      BuildingPreviewPayload.STREAM_CODEC.encode(buffer, payload);
      BuildingPreviewPayload decoded = BuildingPreviewPayload.STREAM_CODEC.decode(buffer);

      assertEquals(payload, decoded);
      assertEquals(LineTieBias.OPPOSITE, decoded.faceTieBias());
      assertEquals(false, decoded.middleConfirmEnabled());
      assertEquals(LineTieBias.OPPOSITE, decoded.modes().faceTieBias());
      assertEquals(
         FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL,
         decoded.modes().faceRasterizationMode()
      );
      assertEquals(placement, decoded.placementContext());
   }
}
