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

import java.util.ArrayList;
import java.util.List;

/**
 * request class for Stiebel heat pump.
 *
 * @author Peter Kreutzer
 */
public class Request {

    private String name;
    private String description;
    private byte[] requestByte;
    private byte[] requestByte2;
    private List<RecordDefinition> recordDefinitionList;

    public Request(String name, String description, byte[] requestByte, byte[] requestByte2) {
        this.name = name;
        this.description = description;
        this.requestByte = requestByte;
        this.requestByte2 = requestByte2;
        this.recordDefinitionList = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public byte[] getRequestByte() {
        return requestByte;
    }

    public byte[] getRequestByte2() {
        return requestByte2;
    }

    public List<RecordDefinition> getRecordDefinitions() {
        return recordDefinitionList;
    }

    public RecordDefinition getRecordDefinitionByChannelId(String channelId) {
        for (RecordDefinition record : recordDefinitionList) {
            if (record.getChannelid().equalsIgnoreCase(channelId)) {
                return record;
            }
        }
        return null;
    }
}
