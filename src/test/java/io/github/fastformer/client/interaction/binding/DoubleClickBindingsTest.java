package io.github.fastformer.client.interaction.binding;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.fastformer.client.interaction.binding.action.BuiltinBindingActions;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.binding.context.ContextFields;
import io.github.fastformer.client.interaction.binding.context.ContextMatcher;
import io.github.fastformer.client.interaction.binding.data.BindingFile;
import io.github.fastformer.client.interaction.binding.input.DoubleClickRecognizer;
import io.github.fastformer.common.raycast.InteractionShapeSnapshot;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoubleClickBindingsTest {
   private static final long START = 1_000_000_000L;
   private static final BindingContext CONTEXT = new BindingContext(Map.of("session", new JsonPrimitive("fastformer:idle")));
   private static final InteractionShapeSnapshot SHAPE = new InteractionShapeSnapshot(BlockPos.ZERO, List.of(new AABB(0, 0, 0, 1, 1, 1)));
   private final Commands commands = new Commands();

   @Test void secondPhysicalPressExecutesDoubleAndCannotPairWithAThirdPress() {
      BindingSystem system = system("", new AtomicLong());
      assertFalse(press(system, START, null));
      release(system, START + 50_000_000L);
      assertTrue(press(system, START + 200_000_000L, null));
      release(system, START + 210_000_000L);
      assertFalse(press(system, START + 220_000_000L, null));
      assertEquals(1, commands.calls.stream().filter("cancel"::equals).count());
   }

   @Test void repeatedPressWithoutReleaseDoesNotBecomeADouble() {
      BindingSystem system = system("", new AtomicLong());
      assertFalse(press(system, START, null));
      assertFalse(press(system, START + 100_000_000L, null));
      assertTrue(commands.calls.isEmpty());
   }

   @Test void reloadAndResetDiscardTheFirstPress() {
      AtomicLong revision = new AtomicLong();
      BindingSystem system = system("", revision);
      press(system, START, null);
      release(system, START + 1);
      revision.incrementAndGet();
      assertFalse(press(system, START + 2, null));
      release(system, START + 3);
      system.reset();
      assertFalse(press(system, START + 4, null));
      assertTrue(commands.calls.isEmpty());
   }

   @Test void distanceBandsUseFirstPressDistanceAndFrozenShape() {
      Object scope = new Object();
      BindingSystem near = system("", new AtomicLong());
      press(near, START, sample(scope, new Vec3(0.99, 0.5, -14.99)));
      release(near, START + 1);
      assertFalse(press(near, START + 250_000_000L, sample(scope, new Vec3(1.09, 0.5, -20))));

      BindingSystem far = system("", new AtomicLong());
      press(far, START, sample(scope, new Vec3(0.99, 0.5, -15)));
      release(far, START + 1);
      assertTrue(press(far, START + 300_000_000L, sample(scope, new Vec3(1.09, 0.5, -10))));
   }

   @Test void changedScopeOrItemCannotCompleteTheOldDouble() {
      BindingSystem system = system("", new AtomicLong());
      press(system, START, sample(new Object(), new Vec3(0.5, 0.5, -2)));
      release(system, START + 1);
      assertFalse(press(system, START + 2, sample(new Object(), new Vec3(0.5, 0.5, -2))));
      assertTrue(commands.calls.isEmpty());
   }

   @Test void configuredWindowIsUsedByOrdinaryButtonEvents() {
      BindingSystem system = system(",\"window_ms\":450", new AtomicLong());
      press(system, START, null);
      release(system, START + 1);
      assertTrue(press(system, START + 450_000_000L, null));
   }

   private BindingSystem system(String inputFields, AtomicLong revision) {
      BindingFile file = BindingFile.parse(JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:double_cancel","context":{"session":"fastformer:idle"},
          "input":{"button":"right","gesture":"double"%s},"action":"fastformer:session/cancel"}]}
         """.formatted(inputFields)));
      assertTrue(file.diagnostics().isEmpty(), file.diagnostics().toString());
      var actions = BuiltinBindingActions.create();
      var rules = file.bindings().stream().map(binding -> new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())).toList();
      return new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), () -> rules, id -> true, revision::get, () -> {});
   }

   private boolean press(BindingSystem system, long time, DoubleClickRecognizer.Sample sample) {
      return system.onButton(1, "right", true, time, () -> CONTEXT, commands, sample);
   }

   private void release(BindingSystem system, long time) {
      system.onButton(1, "right", false, time, CONTEXT, commands);
   }

   private static DoubleClickRecognizer.Sample sample(Object scope, Vec3 eye) {
      return new DoubleClickRecognizer.Sample(scope, "item", SHAPE, eye, new Vec3(0, 0, 1));
   }

   private static final class Commands implements BindingCommands {
      private final List<String> calls = new ArrayList<>();
      public boolean start(net.minecraft.resources.ResourceLocation profile) { calls.add("start"); return true; }
      public boolean point() { return false; }
      public boolean middleConfirm() { return false; }
      public boolean undoPoint() { return false; }
      public boolean cancel() { calls.add("cancel"); return true; }
      public boolean cycleMode() { return false; }
      public void replayPress(long time) { calls.add("replay:" + time); }
   }
}
