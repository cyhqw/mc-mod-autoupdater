package com.cyhqw.mcmodupdater.forge;

import com.cyhqw.mcmodupdater.common.util.ModLog;
import com.mojang.logging.log4j.Logger;

/** Bridges our common-module logger to Forge's Log4j. */
final class Log4jSink implements ModLog.Sink {

    private final Logger logger;

    Log4jSink(Logger logger) {
        this.logger = logger;
    }

    @Override public void info(String message) { logger.info(message); }
    @Override public void warn(String message) { logger.warn(message); }
    @Override public void error(String message, Throwable t) {
        if (t != null) logger.error(message, t); else logger.error(message);
    }
}
