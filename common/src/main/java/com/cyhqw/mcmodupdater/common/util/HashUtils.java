package com.cyhqw.mcmodupdater.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hash helpers. Modrinth prefers SHA1; CurseForge returns MD5+SHA1.
 * We also expose SHA-512 for stronger integrity checks.
 */
public final class HashUtils {

    private HashUtils() {}

    public static String sha1(Path file) throws IOException {
        return hash(file, "SHA-1");
    }

    public static String sha512(Path file) throws IOException {
        return hash(file, "SHA-512");
    }

    public static String sha1Bytes(byte[] data) {
        return hashBytes(data, "SHA-1");
    }

    private static String hash(Path file, String algorithm) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return toHex(md.digest());
    }

    private static String hashBytes(byte[] data, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return toHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available", e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
