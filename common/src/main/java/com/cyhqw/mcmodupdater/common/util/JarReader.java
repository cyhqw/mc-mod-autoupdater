package com.cyhqw.mcmodupdater.common.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read a single file out of a .jar without unpacking it, and small IO helpers.
 */
public final class JarReader {

    private JarReader() {}

    /** Returns the entry content as a UTF-8 string, or {@code null} if not found. */
    public static String readEntry(Path jarPath, String entryName) throws IOException {
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream in = zf.getInputStream(entry)) {
                byte[] data = readAll(in);
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (java.util.zip.ZipException e) {
            return null;
        }
    }

    public static boolean hasEntry(Path jarPath, String entryName) {
        try (ZipFile zf = new ZipFile(jarPath.toFile())) {
            return zf.getEntry(entryName) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Read the entire stream into a byte array. */
    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Stream copy helper for downloads. Returns total bytes copied. */
    public static long copy(InputStream in, OutputStream out, int bufferSize) throws IOException {
        byte[] buf = new byte[bufferSize];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }
}
