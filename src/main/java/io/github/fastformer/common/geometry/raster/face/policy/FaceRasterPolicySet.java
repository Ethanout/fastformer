package io.github.fastformer.common.geometry.raster.face.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** One immutable rule snapshot shared by a preview and its generation job. */
public final class FaceRasterPolicySet {
   public static final FaceRasterPolicySet DEFAULT = new FaceRasterPolicySet(
      FaceRasterPolicies.CURRENT_QUAD, FaceRasterPolicies.CURRENT_POLYGON, FaceRasterPolicies.CURRENT_VOLUME_FACE);
   private final FaceRasterPolicy quad;
   private final FaceRasterPolicy polygon;
   private final FaceRasterPolicy volume;
   private final String hash;

   public FaceRasterPolicySet(FaceRasterPolicy quad, FaceRasterPolicy polygon, FaceRasterPolicy volume) {
      this.quad = Objects.requireNonNull(quad, "quad");
      this.polygon = Objects.requireNonNull(polygon, "polygon");
      this.volume = Objects.requireNonNull(volume, "volume");
      try {
         String canonical = "quad=" + quad.canonicalJson() + "\npolygon=" + polygon.canonicalJson()
            + "\nvolume=" + volume.canonicalJson();
         hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException failure) {
         throw new IllegalStateException("SHA-256 is not available", failure);
      }
   }

   public FaceRasterPolicy quad() { return quad; }
   public FaceRasterPolicy polygon() { return polygon; }
   public FaceRasterPolicy volume() { return volume; }
   public String hash() { return hash; }
   @Override public boolean equals(Object other) {
      return other instanceof FaceRasterPolicySet rules && quad.equals(rules.quad)
         && polygon.equals(rules.polygon) && volume.equals(rules.volume);
   }
   @Override public int hashCode() { return Objects.hash(quad, polygon, volume); }
}
