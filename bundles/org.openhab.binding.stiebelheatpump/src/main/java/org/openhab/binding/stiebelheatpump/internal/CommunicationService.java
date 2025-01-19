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
package org.openhab.binding.stiebelheatpump.internal;

import java.util.List;
import java.util.Map;

import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.Request;

/**
 * @author Robin Windey
 */
public interface CommunicationService {
    public void finalizer();

    public void connect();

    public void disconnect();

    public String getVersion(Request versionRequest) throws StiebelHeatPumpException;

    public Map<String, Object> getRequestData(List<Request> requests) throws StiebelHeatPumpException;

    public Map<String, Object> setTime(Request timeRequest) throws StiebelHeatPumpException;

    public Map<String, Object> readData(Request request) throws StiebelHeatPumpException;

    public String dumpRequest(byte[] request) throws StiebelHeatPumpException;

    public Map<String, Object> writeData(Object newValue, String channelId, RecordDefinition updateRecord)
            throws StiebelHeatPumpException;

    public Map<String, Object> dumpResponse(Request request);

    public byte[] setData(byte[] request) throws StiebelHeatPumpException;
}
