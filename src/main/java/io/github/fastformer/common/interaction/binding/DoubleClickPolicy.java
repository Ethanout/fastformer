package io.github.fastformer.common.interaction.binding;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.fastformer.common.raycast.InteractionShapeSnapshot;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Distance bands and alternative spatial acceptance conditions frozen by the first press. */
public record DoubleClickPolicy(List<Band> tolerance, List<Acceptance> acceptIfAny) {
   public static final DoubleClickPolicy DEFAULT = new DoubleClickPolicy(
      List.of(new Band(0, 15, 200, true, 0, 0), new Band(15, Double.MAX_VALUE, 300, false, 0.5, 0.5)),
      List.of(new Acceptance(2, -1), new Acceptance(-1, 0.5)));

   public static final Codec<DoubleClickPolicy> CODEC = RecordCodecBuilder.<DoubleClickPolicy>create(instance -> instance.group(
      Band.CODEC.listOf().fieldOf("tolerance").forGetter(DoubleClickPolicy::tolerance),
      Acceptance.CODEC.listOf().optionalFieldOf("accept_if_any", DEFAULT.acceptIfAny).forGetter(DoubleClickPolicy::acceptIfAny)
   ).apply(instance, DoubleClickPolicy::new)).validate(DoubleClickPolicy::validate);

   public DoubleClickPolicy {
      tolerance = List.copyOf(tolerance);
      acceptIfAny = List.copyOf(acceptIfAny);
   }

   private static DataResult<DoubleClickPolicy> validate(DoubleClickPolicy policy) {
      if (policy.tolerance.isEmpty()) return DataResult.error(() -> "Double click requires distance bands");
      double nextFrom = 0;
      for (Band band : policy.tolerance) {
         if (band.from != nextFrom || band.below <= band.from) {
            return DataResult.error(() -> "Double-click distance bands must cover the distance range without gaps or overlap");
         }
         nextFrom = band.below;
      }
      if (nextFrom != Double.MAX_VALUE) return DataResult.error(() -> "The last double-click band must have no upper bound");
      if (policy.acceptIfAny.isEmpty()) return DataResult.error(() -> "Double click requires a spatial acceptance condition");
      return DataResult.success(policy);
   }

   public Captured capture(InteractionShapeSnapshot snapshot, Vec3 eye, Vec3 view) {
      BlockHitResult hit = snapshot.clip(eye, view);
      double distance = eye.distanceTo(hit == null ? Vec3.atCenterOf(snapshot.position()) : hit.getLocation());
      Band band = tolerance.stream().filter(candidate -> distance >= candidate.from && distance < candidate.below)
         .findFirst().orElseThrow(() -> new IllegalStateException("No double-click band for distance " + distance));
      double padding = band.exact ? 0 : Math.min(band.edgeMaxBlocks, distance * Math.tan(Math.toRadians(band.edgeDegrees)));
      return new Captured(TimeUnit.MILLISECONDS.toNanos(band.windowMillis), padding, acceptIfAny);
   }

   /** All lengths are blocks, angles are degrees, and intervals are milliseconds. */
   public record Band(double from, double below, int windowMillis, boolean exact, double edgeDegrees, double edgeMaxBlocks) {
      public static final Codec<Band> CODEC = RecordCodecBuilder.<Band>create(instance -> instance.group(
         Codec.doubleRange(0, Double.MAX_VALUE).optionalFieldOf("from", 0.0).forGetter(Band::from),
         Codec.doubleRange(0, Double.MAX_VALUE).optionalFieldOf("below", Double.MAX_VALUE).forGetter(Band::below),
         Codec.intRange(1, 60_000).fieldOf("window_ms").forGetter(Band::windowMillis),
         Codec.BOOL.optionalFieldOf("exact", false).forGetter(Band::exact),
         Codec.doubleRange(0, 90).optionalFieldOf("edge_deg", 0.0).forGetter(Band::edgeDegrees),
         Codec.doubleRange(0, Double.MAX_VALUE).optionalFieldOf("edge_max_blocks", 0.0).forGetter(Band::edgeMaxBlocks)
      ).apply(instance, Band::new));
   }

   public record Acceptance(double viewChangeDegrees, double hitDistanceBlocks) {
      public static final Codec<Acceptance> CODEC = RecordCodecBuilder.<Acceptance>create(instance -> instance.group(
         Codec.doubleRange(0, 180).optionalFieldOf("view_change_deg", -1.0).forGetter(Acceptance::viewChangeDegrees),
         Codec.doubleRange(0, Double.MAX_VALUE).optionalFieldOf("hit_distance_blocks", -1.0).forGetter(Acceptance::hitDistanceBlocks)
      ).apply(instance, Acceptance::new)).validate(condition -> condition.viewChangeDegrees < 0 && condition.hitDistanceBlocks < 0
         ? DataResult.error(() -> "Double-click acceptance requires an angle or hit distance") : DataResult.success(condition));
   }

   public record Captured(long windowNanos, double shapePadding, List<Acceptance> acceptIfAny) {
      public Captured { acceptIfAny = List.copyOf(acceptIfAny); }

      public BlockHitResult clip(InteractionShapeSnapshot snapshot, Vec3 eye, Vec3 view) {
         return snapshot.clip(eye, view, shapePadding);
      }

      public BlockHitResult match(InteractionShapeSnapshot snapshot, Vec3 firstEye, Vec3 firstView, Vec3 eye, Vec3 view) {
         BlockHitResult second = snapshot.clip(eye, view, shapePadding);
         if (second == null) return null;
         BlockHitResult first = snapshot.clip(firstEye, firstView);
         double dot = firstView.normalize().dot(view.normalize());
         for (Acceptance condition : acceptIfAny) {
            if (condition.viewChangeDegrees >= 0 && dot >= Math.cos(Math.toRadians(condition.viewChangeDegrees))) return second;
            if (condition.hitDistanceBlocks >= 0 && first != null
               && first.getLocation().distanceToSqr(second.getLocation()) <= condition.hitDistanceBlocks * condition.hitDistanceBlocks) return second;
         }
         return null;
      }
   }
}
