package io.github.fastformer.fastplace.geometry.movement;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Shared dash traversal contract for server authorization and each client movement step.
 * A future phasing policy must implement both path checks and actual movement, and be
 * selected on both sides. Replacing only path checks would still collide during movement.
 */
public interface ForwardDashTraversal {
   ForwardDashTraversal COLLISION_SAFE = new CollisionSafeDashTraversal();

   /** Returns the traversable fraction in [0, 1], respecting loaded chunks and world boundaries. */
   double clearFraction(Entity traveler, Vec3 movement);

   /** Applies a movement step already checked by this policy. */
   void move(Entity traveler, Vec3 movement);
}
