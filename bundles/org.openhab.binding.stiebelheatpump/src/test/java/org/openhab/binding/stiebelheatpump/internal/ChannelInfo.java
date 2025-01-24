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

/**
 * Represents a channel read from the XML configuration. Used for testing only.
 *
 * @author Robin Windey - Initial Contribution
 */
public class ChannelInfo {
    private final String channelId;
    private final String channelTypeId;
    private final String channelItemType;

    public ChannelInfo(String channelId, String channelTypeId, String channelItemType) {
        this.channelId = channelId;
        this.channelTypeId = channelTypeId;
        this.channelItemType = channelItemType;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getChannelTypeId() {
        return channelTypeId;
    }

    public String getChannelItemType() {
        return channelItemType;
    }
}
