package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FastPlaceSessionTieBiasTest {
   @Test
   void confirmedFaceBiasSurvivesStageChangeAndResetsWhenFacePointIsUndone() {
      FastPlaceSession session = new FastPlaceSession();
      session.addPoint(BlockPos.ZERO, Vec3.ZERO, new Vec3(1.0, 0.0, 0.0));
      session.addPoint(new BlockPos(2, 1, 0), Vec3.ZERO, new Vec3(1.0, 0.0, 0.0));
      session.addPoint(new BlockPos(0, 0, 2), Vec3.ZERO, new Vec3(1.0, 0.0, 0.0));
      session.confirmFaceTieBias(LineTieBias.OPPOSITE);

      session.onStageChanged();
      assertEquals(LineTieBias.OPPOSITE, session.faceTieBias());

      session.undoStep();
      assertEquals(2, session.points().size());
      assertEquals(LineTieBias.DEFAULT, session.faceTieBias());
   }

   @Test
   void destroyingSessionClearsConfirmedFaceBias() {
      FastPlaceSession session = new FastPlaceSession();
      session.confirmFaceTieBias(LineTieBias.OPPOSITE);

      session.onDestroyed();

      assertEquals(LineTieBias.DEFAULT, session.faceTieBias());
   }

   @Test
   void heldModifierOverridesDefaultAtFinalConfirmationWithoutChangingStoredBias() {
      FastPlaceSession session = new FastPlaceSession();

      assertEquals(LineTieBias.DEFAULT, session.effectiveFaceTieBias(false));
      assertEquals(LineTieBias.OPPOSITE, session.effectiveFaceTieBias(true));
      assertEquals(LineTieBias.DEFAULT, session.faceTieBias());

      session.confirmFaceTieBias(LineTieBias.OPPOSITE);
      assertEquals(LineTieBias.OPPOSITE, session.effectiveFaceTieBias(false));
      assertEquals(LineTieBias.OPPOSITE, session.effectiveFaceTieBias(true));
   }
}
