package io.github.fastformer.server.api;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.api.FastFormerEvent;
import io.github.fastformer.common.api.HistoryRecorded;
import io.github.fastformer.common.api.ShapeCommitted;
import io.github.fastformer.server.task.WorldTaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class ServerEventStreamTest {
   @Test void listenerAndBridgeFailuresCannotBreakAnotherSubscription() {
      List<ShapeCommitted> received = new ArrayList<>();
      var stream = new ServerEventStream(event -> { throw new IllegalStateException("external bus failure"); });
      stream.subscribe(FastFormerEvent.class, event -> { throw new AssertionError("extension failed"); });
      stream.subscribe(ShapeCommitted.class, received::add);
      stream.subscribe(HistoryRecorded.class, event -> fail("wrong event type"));
      ShapeCommitted event = event();
      assertDoesNotThrow(() -> stream.publish(event));
      assertEquals(List.of(event), received);
   }

   @Test void closingAnOwnerReleasesItsSubscribersAndCannotAffectAnotherServer() {
      List<FastFormerEvent> first = new ArrayList<>();
      List<FastFormerEvent> second = new ArrayList<>();
      var left = new ServerEventStream(first::add);
      var right = new ServerEventStream(second::add);
      var source = left.source();
      assertFalse(source instanceof ServerEventStream, "Public view must not expose publication");
      left.onDiscard();
      left.onDiscard();
      left.publish(event());
      right.publish(event());
      assertTrue(first.isEmpty());
      assertEquals(1, second.size());
      assertThrows(IllegalStateException.class, () -> source.subscribe(ShapeCommitted.class, ignored -> {}));
   }

   @Test void removingDuringNotificationIsSafeAndNewSubscribersWaitUntilNextEvent() {
      var stream = new ServerEventStream(ignored -> {});
      List<String> calls = new ArrayList<>();
      var removed = new io.github.fastformer.common.api.EventSource.Subscription[1];
      stream.subscribe(ShapeCommitted.class, event -> {
         calls.add("first");
         removed[0].close();
         stream.subscribe(ShapeCommitted.class, ignored -> calls.add("new"));
      });
      removed[0] = stream.subscribe(ShapeCommitted.class, ignored -> calls.add("removed"));
      stream.publish(event());
      assertEquals(List.of("first"), calls);
      stream.publish(event());
      removed[0].close();
      assertEquals(List.of("first", "first", "new"), calls);
   }

   @Test void resumeRetainsTheOriginalServerEventLifetime() {
      var context = new WorldTaskContext(null, UUID.randomUUID());
      assertSame(context.events(), context.withResume(() -> {}).events());
   }

   private static ShapeCommitted event() {
      return new ShapeCommitted(UUID.randomUUID(), UUID.randomUUID(), ResourceLocation.parse("minecraft:overworld"),
         3, ShapeCommitted.Kind.PLACEMENT);
   }
}
