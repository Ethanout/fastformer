package io.github.fastformer.common.geometry.raster.face.policy;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import io.github.fastformer.fastplace.geometry.generation.FaceRasterAdapters;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithms;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class FaceRasterPolicyTest {
    private final FaceRasterAlgorithms algorithms = FaceRasterAdapters.currentAlgorithms();

    @Test void completeDefaultChainsAreRegisteredAndKeepExistingPoliciesAvailable() {
        FaceRasterPolicies.DEFAULT.validate(algorithms);
        assertEquals(ids("axis_rectangle", "normal_plane"), FaceRasterPolicies.DEFAULT.chain(FaceClass.AXIS_RECTANGLE));
        assertEquals(ids("coordinate_plane", "normal_plane"), FaceRasterPolicies.DEFAULT.chain(FaceClass.COORDINATE_PLANE));
        assertEquals(ids("axis_extrusion", "normal_plane"), FaceRasterPolicies.DEFAULT.chain(FaceClass.AXIS_EXTRUSION));
        assertEquals(ids("normal_plane", "translated_scan", "edge_only"), FaceRasterPolicies.DEFAULT.chain(FaceClass.TILTED_PARALLELOGRAM));
        assertEquals(ids("projected_polygon", "edge_only"), FaceRasterPolicies.DEFAULT.chain(FaceClass.POLYGON));
        assertEquals(ids("edge_only"), FaceRasterPolicies.DEFAULT.chain(FaceClass.DEGENERATE));
        FaceRasterPolicies.CURRENT_QUAD.validate(algorithms);
        FaceRasterPolicies.CURRENT_POLYGON.validate(algorithms);
        assertEquals(ids("current_quad"), FaceRasterPolicies.CURRENT_QUAD.chain(FaceClass.AXIS_RECTANGLE));
        assertEquals(ids("current_polygon"), FaceRasterPolicies.CURRENT_POLYGON.chain(FaceClass.AXIS_RECTANGLE));
    }

    @Test void strictReaderAndCodecRoundTripCanonicalPolicy() {
        var expected = FaceRasterPolicies.DEFAULT;
        assertEquals(expected, read(expected.canonicalJson()));
        var codec = FaceRasterPolicy.codec(algorithms);
        var encoded = codec.encodeStart(JsonOps.INSTANCE, expected).getOrThrow();
        assertEquals(expected, codec.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertTrue(codec.parse(JsonOps.INSTANCE, new JsonPrimitive("invalid")).error().isPresent());
    }

    @Test void rejectsUnknownIdsClassesEmptyMissingAndNonStringChains() {
        var unknownId = FaceRasterPolicies.DEFAULT.encode();
        unknownId.getAsJsonObject("chains").getAsJsonArray("axis_rectangle").set(0, new JsonPrimitive("fastformer:not_registered"));
        assertInvalid(unknownId.toString(), "Unknown face raster algorithm");
        var unknownClass = FaceRasterPolicies.DEFAULT.encode();
        unknownClass.getAsJsonObject("chains").add("typo", new com.google.gson.JsonArray());
        assertInvalid(unknownClass.toString(), "Unknown face class");
        var empty = FaceRasterPolicies.DEFAULT.encode();
        empty.getAsJsonObject("chains").add("polygon", new com.google.gson.JsonArray());
        assertInvalid(empty.toString(), "Empty face algorithm chain");
        var missing = FaceRasterPolicies.DEFAULT.encode();
        missing.getAsJsonObject("chains").remove("degenerate");
        assertThrows(IllegalArgumentException.class, () -> read(missing.toString()));
        var wrongType = FaceRasterPolicies.DEFAULT.encode();
        wrongType.getAsJsonObject("chains").getAsJsonArray("polygon").set(0, new JsonPrimitive(1));
        assertInvalid(wrongType.toString(), "Algorithm id must be a string");
        assertInvalid("""
                {"chains":null}
                """, "chains must be an object");
        assertInvalid("""
                {"chains":{},"extra":0}
                """, "Unknown face policy field");
        assertThrows(IllegalArgumentException.class, () -> FaceRasterPolicy.parse(null, algorithms));
    }

    @Test void rejectsDuplicatesCommentsTrailingDocumentsAndMalformedIds() {
        String canonical = FaceRasterPolicies.DEFAULT.canonicalJson();
        String chains = FaceRasterPolicies.DEFAULT.encode().get("chains").toString();
        assertInvalid("""
                {"chains":%s,"chains":%s}
                """.formatted(chains, chains), "Duplicate face policy field");
        assertInvalid(canonical.replace("\"chains\":{", "\"chains\":{\"polygon\":[\"fastformer:edge_only\"],"), "Duplicate face class");
        assertThrows(IllegalArgumentException.class, () -> read("/* comment */" + canonical));
        assertThrows(IllegalArgumentException.class, () -> read(canonical + "{}"));
        assertThrows(IllegalArgumentException.class, () -> read(canonical.replace("fastformer:axis_rectangle", "Fastformer:bad id")));
        assertThrows(IllegalArgumentException.class, () -> read("{chains:" + chains + "}"));
    }

    @Test void canonicalHashIgnoresMapOrderAndPreservesChainOrder() {
        List<FaceClass> order = new ArrayList<>(List.of(FaceClass.values()));
        Collections.reverse(order);
        Map<FaceClass, List<ResourceLocation>> reversed = new LinkedHashMap<>();
        order.forEach(kind -> reversed.put(kind, FaceRasterPolicies.DEFAULT.chain(kind)));
        var same = new FaceRasterPolicy(reversed);
        assertEquals(FaceRasterPolicies.DEFAULT.canonicalJson(), same.canonicalJson());
        assertEquals(FaceRasterPolicies.DEFAULT.stableHash(), same.stableHash());
        assertTrue(same.stableHash().matches("[0-9a-f]{64}"));
        EnumMap<FaceClass, List<ResourceLocation>> changed = new EnumMap<>(same.chains());
        changed.put(FaceClass.TILTED_PARALLELOGRAM, ids("translated_scan", "normal_plane", "edge_only"));
        assertNotEquals(same.stableHash(), new FaceRasterPolicy(changed).stableHash());
        assertTrue(same.canonicalJson().startsWith("{\"chains\":{\"axis_extrusion\":"));
    }

    @Test void copiesInputChainsAndNeverExposesMutablePolicyState() {
        Map<FaceClass, List<ResourceLocation>> source = new EnumMap<>(FaceClass.class);
        List<ResourceLocation> input = new ArrayList<>(ids("edge_only"));
        for (FaceClass kind : FaceClass.values()) source.put(kind, input);
        var policy = new FaceRasterPolicy(source);
        String hash = policy.stableHash();
        input.add(FaceRasterPolicies.id("normal_plane"));
        source.clear();
        assertEquals(ids("edge_only"), policy.chain(FaceClass.POLYGON));
        assertEquals(hash, policy.stableHash());
        assertThrows(UnsupportedOperationException.class, () -> policy.chains().clear());
        assertThrows(UnsupportedOperationException.class, () -> policy.chain(FaceClass.POLYGON).add(FaceRasterPolicies.id("normal_plane")));
        JsonObject encoded = policy.encode();
        encoded.getAsJsonObject("chains").remove("polygon");
        assertEquals(hash, policy.stableHash());
    }

    private FaceRasterPolicy read(String json) { return FaceRasterPolicy.read(new StringReader(json), algorithms); }
    private void assertInvalid(String json, String fragment) {
        var failure = assertThrows(IllegalArgumentException.class, () -> read(json));
        assertTrue(failure.getMessage().contains(fragment), failure::getMessage);
    }
    private static List<ResourceLocation> ids(String... paths) { return java.util.Arrays.stream(paths).map(FaceRasterPolicies::id).toList(); }
}
