package io.github.fastformer.client.interaction.selectiongesture;

import net.minecraft.world.phys.Vec3;

/** Frozen geometry of a pressed selection face. */
public record SelectionFaceHit(Vec3 point, Vec3 normal, double distance, int axis) { }
