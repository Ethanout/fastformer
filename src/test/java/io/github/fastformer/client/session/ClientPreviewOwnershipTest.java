package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.fastplace.session.FastPlaceActivity;
import io.github.fastformer.protocol.s2c.preview.ActivityStatePayload;
import org.junit.jupiter.api.Test;

class ClientPreviewOwnershipTest {
   @Test void separateSessionSystemsNeverShareServerSnapshotsOrRevisions() {
      var first = new ClientSessionSystem();
      var second = new ClientSessionSystem();
      assertNotSame(first.previews(), second.previews());
      first.previews().applyActivity(new ActivityStatePayload(FastPlaceActivity.RESTORE_TASK));
      assertEquals(State.RESTORING, first.modeView().observedPhase());
      assertEquals(State.IDLE, second.modeView().observedPhase());
      assertEquals(1L, first.previews().revision());
      assertEquals(0L, second.previews().revision());
   }

   @Test void snapshotUpdatesAndWorldResetAreVisibleWithoutInitializingTheRenderer() {
      var system = new ClientSessionSystem();
      var before = system.modeView();
      system.previews().applyBuilding(BuildingPreviewSnapshot.inactive());
      system.previews().applyActivity(new ActivityStatePayload(FastPlaceActivity.RESTORE_TASK));
      assertEquals(State.RESTORING, system.modeView().observedPhase());
      assertTrue(before.idleActionsAllowed());
      system.previews().resetConnection();
      assertEquals(before, system.modeView());
      assertEquals(FastPlaceActivity.NONE, system.previews().activity());
   }
}
