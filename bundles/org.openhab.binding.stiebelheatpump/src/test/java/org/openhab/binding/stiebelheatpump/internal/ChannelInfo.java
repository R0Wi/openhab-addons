package org.openhab.binding.stiebelheatpump.internal;

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
