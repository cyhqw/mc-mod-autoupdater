package com.cyhqw.mcmodupdater.common.util;

import java.util.Locale;

/**
 * Tiny logging facade so the common module doesn't depend on a specific
 * logging framework (Fabric uses SLF4J; Forge uses Log4j directly).
 *
 * <p>Platform modules plug in a real {@link Sink} on startup.</p>
 */
public final class ModLog {

    public interface Sink {
        void info(String message);
        void warn(String message);
        void error(String message, Throwable t);
    }

    private static volatile Sink sink = new DefaultSink();

    private ModLog() {
    }

    public static void setSink(Sink s) {
        sink = (s == null) ? new DefaultSink() : s;
    }

    public static void info(String format, Object... args) {
        sink.info(format(format, args));
    }

    public static void warn(String format, Object... args) {
        sink.warn(format(format, args));
    }

    public static void error(String message, Throwable t) {
        sink.error(message, t);
    }

    public static void error(String format, Object... args) {
        sink.error(format(format, args), null);
    }

    private static String format(String format, Object... args) {
        try {
            return String.format(Locale.ROOT, format, args);
        } catch (Exception e) {
            return format;
        }
    }

    /** Fallback sink: writes to stderr. Used before platform init. */
    private static final class DefaultSink implements Sink {
        @Override
        public void info(String m) {
            System.out.println("[mcmodupdater/INFO]  " + m);
        }

        @Override
        public void warn(String m) {
            System.err.println("[mcmodupdater/WARN]  " + m);
        }

        @Override
        public void error(String m, Throwable t) {
            System.err.println("[mcmodupdater/ERROR] " + m);
            if (t != null) {
                t.printStackTrace(System.err);
            }
        }
    }
}
