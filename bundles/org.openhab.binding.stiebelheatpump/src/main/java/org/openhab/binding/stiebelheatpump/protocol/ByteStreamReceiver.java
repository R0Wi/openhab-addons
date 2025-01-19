/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ByteStreamReceiver class that runs the read thread to read bytes from the heat
 * pump connector. Handles only return values from the heat pump (RX), not the TX.
 *
 * @author Peter Kreutzer
 */
public class ByteStreamReceiver implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ByteStreamReceiver.class);

    private final InputStream in;
    private final CircularByteBuffer buffer;
    private Thread taskThread;

    public ByteStreamReceiver(InputStream in, CircularByteBuffer buffer) {
        this.in = in;
        this.buffer = buffer;
    }

    public void startTask() {
        logger.debug("Starting serial thread");
        taskThread = new Thread(this);
        taskThread.start();
    }

    public void stopTask() {
        logger.debug("Stopping serial thread");
        taskThread.interrupt();
        try {
            in.close();
        } catch (IOException e) {
            logger.error("Error while closing COM port.", e);
        }
        try {
            taskThread.join();
        } catch (InterruptedException ex) {
            logger.warn("Stoppping serial thread was interrupted");
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        while (!Thread.interrupted()) {
            try {
                if (in.available() > 0) {
                    byte readByte = (byte) in.read();
                    logger.trace("{}", String.format("Received %02X", readByte));
                    buffer.put(readByte);
                }
                Thread.sleep(3);
            } catch (InterruptedException e) {
                logger.warn("Read from serial thread was interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("Error while reading from COM port. Sleeping for 30s.", e);
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e1) {
                    logger.warn("Read from serial thread was interrupted while sleeping");
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
