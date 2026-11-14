package io.github.fastformer.common.geometry.raster.face.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Owns the complete policy schema; external loaders only supply a reader and algorithm registry. */
final class FaceRasterPolicyJson {
    private FaceRasterPolicyJson() {}

    static FaceRasterPolicy read(Reader source) {
        JsonReader reader = new JsonReader(Objects.requireNonNull(source, "source"));
        reader.setLenient(false);
        try {
            require(reader, JsonToken.BEGIN_OBJECT, "Face policy must be an object");
            reader.beginObject();
            Map<FaceClass, List<ResourceLocation>> chains = null;
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!field.equals("chains")) throw invalid(reader, "Unknown face policy field: " + field);
                if (chains != null) throw invalid(reader, "Duplicate face policy field: chains");
                chains = chains(reader);
            }
            reader.endObject();
            require(reader, JsonToken.END_DOCUMENT, "Trailing content after face policy");
            if (chains == null) throw invalid(reader, "Missing face policy field: chains");
            return new FaceRasterPolicy(chains);
        } catch (IOException | IllegalStateException failure) {
            throw new IllegalArgumentException("Invalid face policy at " + reader.getPath() + ": " + failure.getMessage(), failure);
        }
    }

    private static Map<FaceClass, List<ResourceLocation>> chains(JsonReader reader) throws IOException {
        require(reader, JsonToken.BEGIN_OBJECT, "chains must be an object");
        reader.beginObject();
        EnumMap<FaceClass, List<ResourceLocation>> result = new EnumMap<>(FaceClass.class);
        while (reader.hasNext()) {
            String field = reader.nextName();
            FaceClass kind = Arrays.stream(FaceClass.values()).filter(value -> name(value).equals(field)).findFirst()
                    .orElseThrow(() -> invalid(reader, "Unknown face class: " + field));
            if (result.containsKey(kind)) throw invalid(reader, "Duplicate face class: " + field);
            require(reader, JsonToken.BEGIN_ARRAY, "Algorithm chain must be an array");
            reader.beginArray();
            List<ResourceLocation> ids = new ArrayList<>();
            while (reader.hasNext()) {
                require(reader, JsonToken.STRING, "Algorithm id must be a string");
                String value = reader.nextString();
                ResourceLocation id = ResourceLocation.tryParse(value);
                if (id == null) throw invalid(reader, "Invalid face algorithm id: " + value);
                ids.add(id);
            }
            reader.endArray();
            if (ids.isEmpty()) throw invalid(reader, "Empty face algorithm chain: " + field);
            result.put(kind, List.copyOf(ids));
        }
        reader.endObject();
        return result;
    }

    static JsonObject encode(FaceRasterPolicy policy) {
        JsonObject chains = new JsonObject();
        Arrays.stream(FaceClass.values()).sorted(Comparator.comparing(FaceRasterPolicyJson::name)).forEach(kind -> {
            JsonArray ids = new JsonArray();
            policy.chain(kind).forEach(id -> ids.add(id.toString()));
            chains.add(name(kind), ids);
        });
        JsonObject root = new JsonObject();
        root.add("chains", chains);
        return root;
    }

    private static void require(JsonReader reader, JsonToken token, String message) throws IOException {
        if (reader.peek() != token) throw invalid(reader, message);
    }

    private static IllegalArgumentException invalid(JsonReader reader, String message) {
        return new IllegalArgumentException(message + " at " + reader.getPath());
    }

    private static String name(FaceClass kind) { return kind.name().toLowerCase(Locale.ROOT); }
}
