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
package org.openhab.binding.stiebelheatpump.internal;

import org.openhab.binding.stiebelheatpump.protocol.ProtocolConnector;
import org.openhab.core.io.transport.serial.SerialPortManager;

/**
 * @author Robin Windey - initial contribution
 */
@FunctionalInterface
public interface CommunicationServiceFactory {
    CommunicationService create(SerialPortManager serialPortManager, String serialPortName, int baudRate,
            int waitingTime, ProtocolConnector connector);
}
