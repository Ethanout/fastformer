package io.github.fastformer.client.session.reconnect;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.client.session.reconnect.ReconnectSnapshotWindow.Result.*;

import org.junit.jupiter.api.Test;

class ReconnectSnapshotWindowTest {
   @Test
   void inactiveWindowIgnoresSnapshotsAndRestartDoesNotArmIt() {
      var window = new ReconnectSnapshotWindow();
      window.observeSnapshot();
      window.restartWait();
      assertFalse(window.armed());
      assertEquals(INACTIVE, window.tick());
   }

   @Test
   void timeoutOccursOnTheFortiethTickAndOnlyOnce() {
      var window = new ReconnectSnapshotWindow();
      window.arm();
      for (int tick = 1; tick < 40; tick++) assertEquals(WAITING, window.tick());
      assertEquals(TIMED_OUT, window.tick());
      assertFalse(window.armed());
      assertEquals(INACTIVE, window.tick());
   }

   @Test
   void snapshotWinsAtTimeoutBoundary() {
      var window = new ReconnectSnapshotWindow();
      window.arm();
      for (int tick = 1; tick < 40; tick++) window.tick();
      window.observeSnapshot();
      assertEquals(SNAPSHOT_RECEIVED, window.tick());
      assertEquals(INACTIVE, window.tick());
   }

   @Test
   void rearmAndRestartClearPriorReceiptAndElapsedTicks() {
      var window = new ReconnectSnapshotWindow();
      window.arm();
      window.observeSnapshot();
      window.arm();
      assertEquals(WAITING, window.tick());
      for (int tick = 1; tick < 39; tick++) window.tick();
      window.observeSnapshot();
      window.restartWait();
      for (int tick = 1; tick < 40; tick++) assertEquals(WAITING, window.tick());
      assertEquals(TIMED_OUT, window.tick());
      window.arm();
      window.clear();
      assertEquals(INACTIVE, window.tick());
   }
}
