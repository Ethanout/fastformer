package io.github.fastformer.common.geometry.raster.face.policy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithms;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Complete immutable chain table; invalid policy data fails before a generation request starts. */
public record FaceRasterPolicy(Map<FaceClass, List<ResourceLocation>> chains) {
    public FaceRasterPolicy {
        Objects.requireNonNull(chains, "chains");
        if (chains.size() != FaceClass.values().length || chains.keySet().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Face policy must define each face class exactly once");
        }
        EnumMap<FaceClass, List<ResourceLocation>> copy = new EnumMap<>(FaceClass.class);
        for (FaceClass kind : FaceClass.values()) {
            List<ResourceLocation> chain = chains.get(kind);
            if (chain == null || chain.isEmpty()) throw new IllegalArgumentException("Missing face algorithm chain: " + kind);
            copy.put(kind, List.copyOf(chain));
        }
        chains = Collections.unmodifiableMap(copy);
    }

    public List<ResourceLocation> chain(FaceClass kind) { return chains.get(kind); }

    public void validate(FaceRasterAlgorithms algorithms) {
        Objects.requireNonNull(algorithms, "algorithms");
        chains.values().forEach(chain -> chain.forEach(algorithms::require));
    }

    /** Strict schema reader: rejects duplicate fields, unknown classes, unknown algorithms and trailing content. */
    public static FaceRasterPolicy read(Reader source, FaceRasterAlgorithms algorithms) {
        FaceRasterPolicy policy = FaceRasterPolicyJson.read(source);
        policy.validate(algorithms);
        return policy;
    }

    public static FaceRasterPolicy parse(JsonElement source, FaceRasterAlgorithms algorithms) {
        if (source == null) throw new IllegalArgumentException("Face policy must be an object");
        return read(new StringReader(source.toString()), algorithms);
    }

    /** Factory keeps validation tied to the assembly-owned, frozen algorithm registry. */
    public static Codec<FaceRasterPolicy> codec(FaceRasterAlgorithms algorithms) {
        Objects.requireNonNull(algorithms, "algorithms");
        return Codec.PASSTHROUGH.comapFlatMap(dynamic -> {
            try { return DataResult.success(parse(dynamic.convert(JsonOps.INSTANCE).getValue(), algorithms)); }
            catch (IllegalArgumentException failure) { return DataResult.error(() -> failure.getMessage()); }
        }, policy -> new Dynamic<>(JsonOps.INSTANCE, policy.encode()));
    }

    public JsonObject encode() { return FaceRasterPolicyJson.encode(this); }

    public String canonicalJson() { return encode().toString(); }

    /** SHA-256 of canonical UTF-8 JSON; map insertion order is irrelevant, chain order remains significant. */
    public String stableHash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }
}
