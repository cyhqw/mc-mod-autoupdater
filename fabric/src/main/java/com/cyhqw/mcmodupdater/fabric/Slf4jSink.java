package com.cyhqw.mcmodupdater.fabric;

import com.cyhqw.mcmodupdater.common.util.ModLog;
import org.slf4j.Logger;

/** Bridges our common-module logger to SLF4J (which Fabric bundles). */
final class Slf4jSink implements ModLog.Sink {

    private final Logger logger;

    Slf4jSink(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
    }

    @Override
    public void error(String message, Throwable t) {
        if (t != null) {
            logger.error(message, t);
        } else {
            logger.error(message);
        }
    }
}
