package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.EntryPressGuard;

import io.github.fastformer.common.raycast.InteractionShapeSnapshot;
import io.github.fastformer.client.interaction.binding.input.*;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.entry.IdleCommand;
import io.github.fastformer.client.interaction.binding.action.entry.IdleEntryParameters;
import io.github.fastformer.client.interaction.binding.context.IdleBindingContext;
import io.github.fastformer.client.interaction.binding.context.IdleBindingContext.Fact;
import io.github.fastformer.testkit.BindingFixtures;
import java.util.EnumSet;
import java.util.Set;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleEntryCoexistenceTest {
   private static final long PRESS = 1_000_000_000L;
   private static final Vec3 VIEW = new Vec3(0, 0, 1);
   private static final InteractionShapeSnapshot SHAPE = new InteractionShapeSnapshot(BlockPos.ZERO, List.of(new AABB(0, 0, 0, 1, 1, 1)));
   private static final InputPattern HOLD = new InputPattern("right", "hold", 200, "deferred");

   @Test void nearSecondPressPrecedesHoldAndTinker() throws Exception {
      var gesture = new IdleEntryGesture();
      Vec3 eye = new Vec3(0.5, 0.5, -3);
      gesture.remember(1, PRESS, SHAPE, eye, VIEW, this, null);
      assertNotNull(gesture.secondPress(PRESS + 190_000_000L, eye, VIEW));
      assertEquals(IdleCommand.ENTER_ANCHOR, choose(Fact.ANCHOR, Fact.TINKER));
   }

   @Test void fifteenToTwentyBlockSecondPressKeepsThreeHundredMillisecondsAndEdgeMargin() {
      for (double distance : new double[]{15, 17, 20}) {
         var gesture = new IdleEntryGesture();
         Vec3 eye = new Vec3(0.99, 0.5, -distance);
         gesture.remember(1, PRESS, SHAPE, eye, VIEW, this, null);
         Vec3 secondEye = eye.add(0.1, 0, 0);
         assertNull(SHAPE.clip(secondEye, VIEW));
         assertNotNull(gesture.secondPress(PRESS + 300_000_000L, secondEye, VIEW));
         assertNull(gesture.secondPress(PRESS + 300_000_001L, secondEye, VIEW));
      }
   }

   @Test void distantSingleAndAltPreserveTheirEntryGuard() throws Exception {
      assertEquals(IdleCommand.ENTER_SURFACE, choose(Fact.DISTANT, Fact.ANCHOR, Fact.TINKER));
      assertEquals(IdleCommand.ENTER_EMBEDDED, choose(Fact.ALT, Fact.DISTANT, Fact.ANCHOR, Fact.TINKER));
      var guard = new EntryPressGuard();
      guard.arm(PRESS);
      assertTrue(guard.capture(1, 1, PRESS + 100_000_000L));
      assertTrue(guard.capture(0, 1, PRESS + 400_000_000L));
   }

   @Test void holdReleaseHasNoEntryGuardToSwallowIt() {
      var arbiter = new ButtonArbiter<String>();
      var guard = new EntryPressGuard();
      arbiter.press(1, PRESS, HOLD, "frozen press");
      assertEquals(ButtonArbiter.Kind.START, arbiter.tick(PRESS + 200_000_000L).getFirst().kind());
      assertFalse(guard.capture(0, 1, PRESS + 250_000_000L));
      assertEquals(ButtonArbiter.Kind.RELEASE, arbiter.release(1, PRESS + 250_000_000L).getFirst().kind());
   }

   @Test void endingHoldClearsThePairingAndNextClickStartsFresh() throws Exception {
      var gesture = new IdleEntryGesture();
      Vec3 eye = new Vec3(0.5, 0.5, -3);
      gesture.remember(1, PRESS, SHAPE, eye, VIEW, this, null);
      gesture.clear();
      assertNull(gesture.secondPress(PRESS + 50_000_000L, eye, VIEW));
      assertEquals(IdleCommand.HOLD, choose());
   }

   @Test void passthroughAndEntityDoNotStartADeferredHold() throws Exception {
      assertEquals(IdleCommand.REMEMBER, choose(Fact.PASSTHROUGH));
      assertEquals(IdleCommand.CLEAR, choose(Fact.ENTITY));
   }

   private static IdleCommand choose(Fact... values) throws Exception {
      var facts = EnumSet.of(Fact.OUTLINE, Fact.PLACEABLE);
      facts.addAll(List.of(values));
      var selected = new IdleCommand[1];
      BindingFixtures.system(BindingFixtures.defaults("idle")).onSignalsResult("right", Set.of("press", "double"),
         new IdleBindingContext(facts).freeze(), new BindingCommands() {
            public BindingAction.Result idle(IdleCommand command, IdleEntryParameters parameters) {
               selected[0] = command;
               return command == IdleCommand.CLEAR || command == IdleCommand.REMEMBER
                  ? BindingAction.Result.HANDLED_PASS_VANILLA : BindingAction.Result.HANDLED;
            }
            public void replayPress(long time) { fail("Idle resource must not replay a press"); }
         });
      return selected[0];
   }
}
