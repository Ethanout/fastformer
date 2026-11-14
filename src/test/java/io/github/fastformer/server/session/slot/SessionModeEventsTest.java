package io.github.fastformer.server.session.slot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.api.ModeChanged;
import io.github.fastformer.common.mode.ModeId;
import io.github.fastformer.server.api.ServerEventStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class SessionModeEventsTest {
   private static final ResourceLocation REPOSITORY = ResourceLocation.parse("fastformer:geometry");

   @Test void sameMutableSessionSwitchesModeWithoutLosingItsPreviousIdentity() {
      List<ModeChanged> events = new ArrayList<>();
      var sessions = new PlayerSessions<MutableMode>(REPOSITORY, value -> value.id, events::add);
      UUID owner = UUID.randomUUID();
      var session = new MutableMode(ModeId.of("geometry/wall"));
      sessions.activate(owner, session);
      sessions.activate(owner, session);
      ModeId first = session.id;
      session.id = ModeId.of("geometry/polyhedron");
      sessions.activate(owner, session);
      assertEquals(2, events.size());
      assertEquals(Optional.of(first), events.get(1).previous());
      assertEquals(Optional.of(session.id), events.get(1).current());
      assertEquals(owner, events.get(1).owner());
      assertEquals(REPOSITORY, events.get(1).repository());
      sessions.end(owner);
      sessions.end(owner);
      sessions.discard(owner);
      assertEquals(3, events.size(), "Absent sessions must not emit another end or discard");
   }

   @Test void parkRestoreAndDiscardReportOnlyActualActiveModeChanges() {
      List<ModeChanged> events = new ArrayList<>();
      var sessions = new PlayerSessions<ModeId>(REPOSITORY, value -> value, events::add);
      UUID owner = UUID.randomUUID();
      sessions.activate(owner, ModeId.of("first"));
      sessions.park(owner, Level.OVERWORLD);
      sessions.park(owner, Level.OVERWORLD);
      sessions.restore(owner, Level.NETHER);
      sessions.restore(owner, Level.OVERWORLD);
      sessions.restore(owner, Level.OVERWORLD);
      sessions.clear();
      sessions.clear();
      assertEquals(List.of(ModeChanged.Cause.OPEN, ModeChanged.Cause.PARK, ModeChanged.Cause.RESTORE,
         ModeChanged.Cause.DISCARD), events.stream().map(ModeChanged::cause).toList());
      assertTrue(events.getLast().current().isEmpty());
      assertEquals(0, sessions.size());
   }

   @Test void notificationFailureLeavesRepositoryStateAndSubsequentListenersIntact() {
      var stream = new ServerEventStream(ignored -> {});
      List<ModeChanged> events = new ArrayList<>();
      stream.subscribe(ModeChanged.class, event -> { throw new IllegalArgumentException("subscriber"); });
      stream.subscribe(ModeChanged.class, events::add);
      var sessions = new PlayerSessions<ModeId>(REPOSITORY, value -> value, stream::publish);
      UUID owner = UUID.randomUUID();
      ModeId mode = ModeId.of("wall");
      assertDoesNotThrow(() -> sessions.activate(owner, mode));
      assertEquals(mode, sessions.active(owner));
      assertDoesNotThrow(() -> sessions.end(owner));
      assertEquals(2, events.size());
      assertNull(sessions.active(owner));
   }

   private static final class MutableMode {
      private ModeId id;
      private MutableMode(ModeId id) { this.id = id; }
   }
}
