package io.github.fastformer.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Reads symbolic type references, including signatures, annotations, constants and method handles. */
final class BytecodeDependencies {
    private BytecodeDependencies() {}

    static Map<String, Set<String>> read(Collection<Path> roots) throws IOException {
        Map<String, Set<String>> result = new TreeMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) throw new IOException("Missing compiled class directory: " + root);
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                    read(Files.readAllBytes(file), result);
                }
            }
        }
        if (result.isEmpty()) throw new IOException("No compiled classes were found");
        return result;
    }

    static void read(byte[] bytecode, Map<String, Set<String>> result) {
        ClassReader reader = new ClassReader(bytecode);
        String owner = topLevel(reader.getClassName());
        if (owner.endsWith("package-info") || owner.equals("module-info")) return;
        Set<String> references = result.computeIfAbsent(owner, ignored -> new TreeSet<>());
        reader.accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override public String map(String internalName) {
                String target = topLevel(internalName);
                if (!target.equals(owner)) references.add(target);
                return internalName;
            }
        }), 0);
    }

    private static String topLevel(String internalName) {
        int nested = internalName.indexOf('$');
        return (nested < 0 ? internalName : internalName.substring(0, nested)).replace('/', '.');
    }
}
