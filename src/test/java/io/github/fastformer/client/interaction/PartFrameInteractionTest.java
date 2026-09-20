package io.github.fastformer.client.interaction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PartFrameInteractionTest {
   private static final AABB BOUNDS = new AABB(0, 0, 0, 4, 4, 4);

   @Test
   void normalPickingUsesEdgesAndControlAlsoUsesFaces() {
      var frame = PartFrameInteraction.create(UUID.randomUUID(), 1, BOUNDS);
      assertSame(BOUNDS, frame.require(InteractionComponents.WORLD_BOUNDS));
      Vec3 view = new Vec3(0, 0, 1);
      assertNotNull(PartFrameInteraction.hit(frame, new Vec3(0.05, 2, -2), view, 10, false));
      assertNull(PartFrameInteraction.hit(frame, new Vec3(2, 2, -2), view, 10, false));
      assertNotNull(PartFrameInteraction.hit(frame, new Vec3(2, 2, -2), view, 10, true));
      assertNull(PartFrameInteraction.hit(frame, new Vec3(2, 2, -2), view, 1, true));
   }

   @Test
   void frameAndLabelHaveDistinctIdentitiesForTheSamePart() {
      UUID owner = UUID.randomUUID();
      var frame = PartFrameInteraction.create(owner, 7, BOUNDS);
      var moved = PartFrameInteraction.create(owner, 7, BOUNDS.move(100, 0, 0));
      var label = PartLabelInteraction.create(owner, 7, 1, BOUNDS);
      assertEquals(frame.id(), moved.id());
      assertNotEquals(frame.id(), label.id());
      assertNotEquals(frame.id(), PartFrameInteraction.create(UUID.randomUUID(), 7, BOUNDS).id());
      assertEquals(BOUNDS, frame.require(InteractionComponents.WORLD_BOUNDS));
   }

   @Test
   void absentFrameCannotBePicked() {
      assertNull(PartFrameInteraction.hit(null, Vec3.ZERO, new Vec3(0, 0, 1), 10, true));
   }
}
