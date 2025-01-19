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

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.stiebelheatpump.exception.StiebelHeatPumpException;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.Request;

/**
 * @author Robin Windey - initial contribution
 */
public interface CommunicationService extends AutoCloseable {
    @Override
    public void close();

    public void connect();

    public String getVersion(Request versionRequest) throws StiebelHeatPumpException;

    public Map<String, Object> getRequestData(List<Request> requests, @Nullable Runnable beforeNextRequestCallback)
            throws StiebelHeatPumpException;

    public Map<String, Object> setTime(Request timeRequest) throws StiebelHeatPumpException;

    public String dumpRequest(byte[] request) throws StiebelHeatPumpException;

    public Map<String, Object> writeData(Object newValue, RecordDefinition recordDefinition);

    public Map<String, Object> writeTimeQuaterPair(Object startValue, Object endValue,
            RecordDefinition recordDefinitionStart, RecordDefinition recordDefinitionEnd);

    public Map<String, Object> dumpResponse(Request request);
}
