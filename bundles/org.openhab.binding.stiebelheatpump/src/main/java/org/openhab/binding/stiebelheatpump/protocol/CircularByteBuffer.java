/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.stiebelheatpump.protocol;

import org.openhab.binding.stiebelheatpump.exception.StiebelHeatPumpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CircularByteBuffer class providing a buffer that is synchronized with the
 * received bytes from heat pump connector
 *
 * @author Peter Kreutzer - initial contribution
 */
public class CircularByteBuffer {

    private final Logger logger = LoggerFactory.getLogger(CircularByteBuffer.class);

    private static final int WAIT_MS = 10;
    private static final int RETRY = 100;

    private final byte[] buffer;

    private int readPos = 0;
    private int writePos = 0;
    private int currentSize = 0;
    private boolean running = true;

    public CircularByteBuffer(int size) {
        buffer = new byte[size];
    }

    public byte get() throws StiebelHeatPumpException {
        logger.trace("get (Thread {} {})", Thread.currentThread().getId(), Thread.currentThread().getName());
        if (!waitForData()) {
            throw new StiebelHeatPumpException("No data available!");
        }
        byte result;
        synchronized (buffer) {
            result = buffer[readPos];
            currentSize--;
            if (++readPos >= buffer.length) {
                readPos = 0;
            }
        }
        return result;
    }

    public void put(byte b) {
        logger.trace("put (Thread {} {})", Thread.currentThread().getId(), Thread.currentThread().getName());
        synchronized (buffer) {
            buffer[writePos] = b;
            currentSize++;
            if (++writePos >= buffer.length) {
                writePos = 0;
            }
        }
    }

    public void stop() {
        logger.trace("stop");
        running = false;
    }

    private boolean waitForData() {
        logger.trace("waitForData");
        int timeOut = 0;
        while (isEmpty() && timeOut < RETRY && running) {
            try {
                logger.trace("sleep");
                Thread.sleep(WAIT_MS);
                timeOut++;
            } catch (Exception e) {
                logger.error("Error while waiting for new data", e);
            }
        }

        return !isEmpty();
    }

    private boolean isEmpty() {
        return currentSize <= 0;
    }
}
