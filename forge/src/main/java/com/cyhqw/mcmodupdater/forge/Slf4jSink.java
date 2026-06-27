package com.cyhqw.mcmodupdater.forge;

import com.cyhqw.mcmodupdater.common.util.ModLog;
import org.slf4j.Logger;

/** Bridges our common-module logger to SLF4J (Forge bundles it via Log4j-to-SLF4J). */
final class Slf4jSink implements ModLog.Sink {

    private final Logger logger;

    Slf4jSink(Logger logger) {
        this.logger = logger;
    }

    @Override public void info(String message) { logger.info(message); }
    @Override public void warn(String message) { logger.warn(message); }
    @Override public void error(String message, Throwable t) {
        if (t != null) logger.error(message, t); else logger.error(message);
    }
}
