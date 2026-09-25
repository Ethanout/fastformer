package io.github.fastformer.network;

import io.github.fastformer.network.payload.operation.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RaySnapshotValidationTest {
   @Test void rejectsInvalidRayBeforeWorldAccess() {
      for (Vec3 invalid : new Vec3[] {Vec3.ZERO, new Vec3(Double.NaN, 0, 0), new Vec3(Double.POSITIVE_INFINITY, 0, 0)}) {
         assertThrows(IllegalArgumentException.class, () -> new OperationInsertPointPayload(1, 0,
            OperationCallbackScope.unscoped(), Vec3.ZERO, invalid));
      }
      assertThrows(IllegalArgumentException.class, () -> new OperationInsertPointPayload(1, -1,
         OperationCallbackScope.unscoped(), Vec3.ZERO, new Vec3(1,0,0)));
      assertFalse(RaySnapshotValidation.valid(new Vec3(Double.NaN,0,0), new Vec3(1,0,0)));
      assertFalse(RaySnapshotValidation.near(new Vec3(1000,0,0), Vec3.ZERO, 5));
      assertTrue(RaySnapshotValidation.near(new Vec3(2,0,0), Vec3.ZERO, 5));
   }
}
