package com.cyhqw.mcmodupdater.common.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 扫描目录下的 {@code .jar} 文件并解析元数据。
 *
 * <p>默认递归子目录（部分加载器支持嵌套模组组织）。可通过 {@code scanRecursively=false}
 * 关闭，仅扫描顶层。</p>
 */
public final class ModScanner {

    private ModScanner() {}

    /** 递归扫描，不包含 disabled。 */
    public static List<ModMetadata> scan(Path modsDir) throws IOException {
        return scan(modsDir, false, true);
    }

    /** 递归扫描，可选包含 disabled。 */
    public static List<ModMetadata> scan(Path modsDir, boolean includeDisabled) throws IOException {
        return scan(modsDir, includeDisabled, true);
    }

    /**
     * 扫描目录。
     *
     * @param modsDir         要扫描的目录
     * @param includeDisabled 是否包含 .jar.disabled 文件
     * @param scanRecursively 是否递归子目录
     */
    public static List<ModMetadata> scan(Path modsDir, boolean includeDisabled, boolean scanRecursively)
            throws IOException {
        List<ModMetadata> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return result;
        }
        try (Stream<Path> stream = scanRecursively ? Files.walk(modsDir) : Files.list(modsDir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String n = p.getFileName().toString().toLowerCase();
                      return n.endsWith(".jar") || (includeDisabled && n.endsWith(".jar.disabled"));
                  })
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
        return scan(modsDir, true, true);
    }
}
