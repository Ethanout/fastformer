package io.github.fastformer.server.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.common.mode.ModeId;
import io.github.fastformer.core.command.IntentResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ServerModeLifecycleTest {
   @Test void openedStateReachesInputPreviewConfirmationAndLifecycleHooks() {
      var repository = new MemoryRepository();
      var mode = new RecordingMode(repository);
      String draft = new String("draft");
      assertSame(draft, mode.open(null, draft));
      assertSame(draft, repository.current);
      assertTrue(mode.active(null));
      assertEquals(ModeInputResult.CONSUMED, mode.input(null, ModeInput.RIGHT_BLOCK));
      assertTrue(mode.confirm(null).handled());
      mode.sync(null);
      mode.tick(null);
      mode.detach(null);
      mode.dimensionChanged(null);
      mode.cancel(null);
      assertSame(draft, mode.received);
      assertEquals(List.of("open", "input", "confirm", "preview", "tick", "detach", "dimension", "cancel"), mode.events);
      assertEquals(1, repository.cancels);
      assertFalse(mode.active(null));
      assertFalse(mode.confirm(null).handled());
      assertEquals(ModeInputResult.NEXT, mode.input(null, ModeInput.RIGHT_BLOCK));
      mode.sync(null);
      mode.tick(null);
      assertEquals(8, mode.events.size());
   }

   @Test void aSharedRepositoryOnlyDispatchesToTheModeThatOwnsTheDraft() {
      var repository = new MemoryRepository();
      var first = new RecordingMode(repository);
      var other = new RecordingMode(repository) {
         @Override protected boolean owns(String session) { return session.startsWith("other"); }
      };
      first.open(null, "draft");
      other.sync(null);
      other.tick(null);
      other.cancel(null);
      assertFalse(other.confirm(null).handled());
      assertTrue(other.events.isEmpty());
      assertEquals(0, repository.cancels);
      assertThrows(IllegalArgumentException.class, () -> other.open(null, "draft"));
      assertEquals("draft", repository.current);
   }

   @Test void retiredModesRejectOpeningAndStopAllDispatch() {
      var repository = new MemoryRepository();
      var mode = new RecordingMode(repository);
      mode.open(null, "draft");
      mode.retire();
      mode.retire();
      assertThrows(IllegalStateException.class, () -> mode.open(null, "late"));
      mode.sync(null);
      mode.tick(null);
      mode.cancel(null);
      mode.detach(null);
      assertFalse(mode.confirm(null).handled());
      assertEquals(ModeInputResult.NEXT, mode.input(null, ModeInput.RIGHT_BLOCK));
      assertFalse(mode.active(null));
      assertEquals(List.of("open", "close"), mode.events);
      assertEquals(0, repository.cancels);
      assertEquals("draft", repository.current);
   }

   @Test void environmentModesDoNotBecomeActiveOrReceiveEditorTicks() {
      var repository = new MemoryRepository();
      var mode = new RecordingMode(repository) {
         @Override protected boolean activeSession(String session) { return false; }
      };
      mode.open(null, "environment");
      mode.tick(null);
      assertFalse(mode.active(null));
      assertEquals(List.of("open"), mode.events);
   }

   private static class RecordingMode extends ServerMode<String> {
      final List<String> events = new ArrayList<>();
      String received;
      RecordingMode(MemoryRepository repository) { super(ModeId.of("test_lifecycle"), InteractionState.BUILDING, repository); }
      private void record(String event, String session) { received = session; events.add(event); }
      @Override protected void onOpen(ServerPlayer player, String session) { record("open", session); }
      @Override protected ModeInputResult onInput(ServerPlayer player, String session, ModeInput input) {
         record("input", session);
         return ModeInputResult.CONSUMED;
      }
      @Override protected IntentResult onConfirm(ServerPlayer player, String session) { record("confirm", session); return IntentResult.success(); }
      @Override protected void onPreview(ServerPlayer player, String session) { record("preview", session); }
      @Override protected void onTick(ServerPlayer player, String session) { record("tick", session); }
      @Override protected void onCancel(ServerPlayer player, String session) { record("cancel", session); }
      @Override protected void onDetach(ServerPlayer player, String session) { record("detach", session); }
      @Override protected void onDimensionChanged(ServerPlayer player, String session) { record("dimension", session); }
      @Override protected void onClose() { events.add("close"); }
   }

   private static final class MemoryRepository implements ServerEditor<String> {
      private String current;
      private int cancels;
      @Override public Optional<String> session(ServerPlayer player) { return Optional.ofNullable(current); }
      @Override public void activate(ServerPlayer player, String session) { current = session; }
      @Override public void cancel(ServerPlayer player) { cancels++; current = null; }
      @Override public void remove(ServerPlayer player) { current = null; }
      @Override public void parkDimensionSession(UUID owner, ResourceKey<Level> dimension) {}
      @Override public void restoreDimensionSession(UUID owner, ResourceKey<Level> dimension) {}
      @Override public void sync(ServerPlayer player) {}
      @Override public void onDiscard() { current = null; }
   }
}
