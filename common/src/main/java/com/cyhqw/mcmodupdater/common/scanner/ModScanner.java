package com.cyhqw.mcmodupdater.common.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans a directory for {@code .jar} files and returns their parsed metadata.
 *
 * <p>The directory is typically the Minecraft instance's {@code mods/} folder.
 * Files in subdirectories are also included (some loaders support nested
 * mod organization).</p>
 */
public final class ModScanner {

    private ModScanner() {}

    public static List<ModMetadata> scan(Path modsDir) throws IOException {
        List<ModMetadata> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.walk(modsDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                  .filter(p -> !p.getFileName().toString().endsWith(".disabled"))
                  .forEach(p -> {
                      try {
                          result.add(ModMetadata.fromJar(p));
                      } catch (Exception e) {
                          // Fall back to filename-only metadata if jar parsing fails.
                          long sz;
                          try { sz = Files.size(p); } catch (IOException ignored) { sz = 0L; }
                          result.add(ModMetadata.fromFilename(p.getFileName().toString(), sz));
                      }
                  });
        }
        return result;
    }

    /** Same as {@link #scan(Path)} but also disabled jars (.jar.disabled). */
    public static List<ModMetadata> scanIncludingDisabled(Path modsDir) throws IOException {
        List<ModMetadata> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return result;
        try (Stream<Path> stream = Files.walk(modsDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String n = p.getFileName().toString().toLowerCase();
                      return n.endsWith(".jar") || n.endsWith(".jar.disabled");
                  })
                  .forEach(p -> {
                      try {
                          result.add(ModMetadata.fromJar(p));
                      } catch (Exception e) {
                          result.add(ModMetadata.fromFilename(p.getFileName().toString(), 0L));
                      }
                  });
        }
        return result;
    }
}
