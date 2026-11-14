package io.github.fastformer.client.session.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldSessionLifecycleTest {
   private static final List<String> ORDER = List.of("input", "source-mask", "preview-session", "operation",
      "interaction-cache", "feedback", "world-render-state");

   @Test
   void leaveKeepsTheExistingCleanupOrder() {
      var participants = new RecordingParticipants();
      new WorldSessionLifecycle(participants).onLeave();
      assertEquals(ORDER, participants.calls);
   }

   @Test
   void failuresDoNotSkipOtherParticipantsAndAreReportedTogether() {
      var participants = new RecordingParticipants();
      participants.failures = List.of("input", "operation");
      var lifecycle = new WorldSessionLifecycle(participants);
      var failure = assertThrows(IllegalStateException.class, lifecycle::onLeave);
      assertEquals("input", failure.getMessage());
      assertEquals(1, failure.getSuppressed().length);
      assertEquals("operation", failure.getSuppressed()[0].getMessage());
      assertEquals(ORDER, participants.calls);
      participants.calls.clear();
      participants.failures = List.of();
      lifecycle.onLeave();
      assertEquals(ORDER, participants.calls);
   }

   @Test
   void repeatedBoundariesDoNotRegisterOrLoseResources() {
      var participants = new RecordingParticipants();
      var lifecycle = new WorldSessionLifecycle(participants);
      lifecycle.onLeave();
      lifecycle.onLeave();
      var expected = new ArrayList<>(ORDER);
      expected.addAll(ORDER);
      assertEquals(expected, participants.calls);
   }

   private static final class RecordingParticipants implements WorldSessionLifecycle.Participants {
      final List<String> calls = new ArrayList<>();
      List<String> failures = List.of();

      private void record(String name) {
         calls.add(name);
         if (failures.contains(name)) throw new IllegalStateException(name);
      }

      @Override public void endInputSession() { record("input"); }
      @Override public void clearSourceMask() { record("source-mask"); }
      @Override public void resetPreviewSession() { record("preview-session"); }
      @Override public void disconnectOperation() { record("operation"); }
      @Override public void clearInteractionCache() { record("interaction-cache"); }
      @Override public void clearFeedback() { record("feedback"); }
      @Override public void clearWorldRenderState() { record("world-render-state"); }
   }
}
