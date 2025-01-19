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

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNull;
import org.openhab.binding.stiebelheatpump.exception.StiebelHeatPumpException;
import org.openhab.binding.stiebelheatpump.internal.SerialPortNotFoundException;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.io.transport.serial.UnsupportedCommOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * connector for serial port communication.
 *
 * @author Evert van Es (originaly copied from)
 * @author Peter Kreutzer
 * @author Robin Windey - refactored
 */

public class SerialConnector implements ProtocolConnector {

    private static final Logger logger = LoggerFactory.getLogger(SerialConnector.class);

    private InputStream in = null;
    private DataOutputStream out = null;
    private SerialPort serialPort = null;
    private ByteStreamReceiver byteStreamReceiver = null;
    private CircularByteBuffer buffer;

    @Override
    public void connect(SerialPortManager portManager, String device, int baudrate) throws StiebelHeatPumpException {
        logger.debug("Connecting to serial port {}", device);
        try {
            SerialPortIdentifier portIdentifier = portManager.getIdentifier(device);
            if (portIdentifier == null) {
                throw new SerialPortNotFoundException(device);
            }

            this.serialPort = portIdentifier.open(this.getClass().getName(), 2000);
            if (this.serialPort == null) {
                throw new StiebelHeatPumpException(String.format("Could not open serial port %s", device));
            }
            setSerialPortParameters(baudrate);

            this.in = serialPort.getInputStream();
            this.out = new DataOutputStream(serialPort.getOutputStream());

            this.out.flush();

            this.buffer = new CircularByteBuffer(Byte.MAX_VALUE * Byte.MAX_VALUE + 2 * Byte.MAX_VALUE);
            this.byteStreamReceiver = new ByteStreamReceiver(in, buffer);
            this.byteStreamReceiver.startTask();

        } catch (IOException e) {
            @NonNull
            Stream<@NonNull SerialPortIdentifier> ports = portManager.getIdentifiers();
            throw new StiebelHeatPumpException(
                    "Serial port " + device + "with given name does not exist. Available ports: " + ports.toString(),
                    e);
        } catch (Exception e) {
            throw new StiebelHeatPumpException("Could not init port : " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnect() {
        logger.debug("Disconnecting serial connection");

        if (byteStreamReceiver != null) {
            logger.debug("Interrupt serial connection {}", byteStreamReceiver.getClass().getName());
            byteStreamReceiver.stopTask();
        }

        if (buffer != null) {
            logger.debug("Close buffer {}", buffer.getClass().getName());
            buffer.stop();
        }

        if (out != null) {
            try {
                logger.debug("Close output stream {}", out.getClass().getName());
                out.flush();
                out.close();
            } catch (IOException e) {
                logger.error("Could not close output stream", e);
            }
        }

        if (serialPort != null) {
            logger.debug("Close serial port {}", serialPort.getClass().getName());
            serialPort.close();
        }

        logger.debug("Disconnected");
    }

    @Override
    public byte get() throws StiebelHeatPumpException {
        return buffer.get();
    }

    @Override
    public void write(byte[] data) throws StiebelHeatPumpException {
        try {
            String dataStr = DataParser.bytesToHex(data, true);
            logger.trace("Send request message : {} (Thread {} {})", dataStr, Thread.currentThread().getId(),
                    Thread.currentThread().getName());
            out.write(data);
            out.flush();
        } catch (IOException e) {
            throw new StiebelHeatPumpException("Could not write " + e.getMessage(), e);
        }
    }

    @Override
    public void write(byte data) throws StiebelHeatPumpException {
        try {
            String byteStr = String.format("Send single byte request message %02X", data);
            logger.trace("{} (Thread {} {})", byteStr, Thread.currentThread().getId(),
                    Thread.currentThread().getName());
            out.write(data);
            out.flush();
        } catch (IOException e) {
            throw new StiebelHeatPumpException("Could not write " + e.getMessage(), e);
        }
    }

    /**
     * Sets the serial port parameters to xxxxbps-8N1
     *
     * @param baudrate
     *            used to initialize the serial connection
     */
    protected void setSerialPortParameters(int baudrate) throws IOException {
        try {
            // Set serial port to xxxbps-8N1
            serialPort.setSerialPortParams(baudrate, SerialPort.DATABITS_8, SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE);
        } catch (UnsupportedCommOperationException ex) {
            throw new IOException("Unsupported serial port parameter for serial port");
        }
    }
}
