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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.State;

/**
 * Test for StiebelHeatPumpHandler class.
 *
 * @author Robin Windey - Initial Contribution
 */
@ExtendWith(MockitoExtension.class)
public class StiebelHeatPumpHandlerTest {
    private @Mock Thing thing;
    private @Mock ThingTypeUID thingTypeUID;
    private @Mock SerialPortManager serialPortManager;
    private @Mock SerialPortIdentifier serialPortIdentifier;
    private @Mock ConfigFileLoader configFileLoader;
    private @Mock CommunicationServiceFactory communicationServiceFactory;
    private @Mock CommunicationService communicationService;
    private @Mock ScheduledExecutorService scheduler;
    private @Mock ThingHandlerCallback thingHandlerCallback;
    private @Mock Channel channelStart;
    private @Mock Channel channelEnd;

    @BeforeEach
    public void setUp() {
        // Execute everything in the main thread, immediately
        when(scheduler.schedule(any(Runnable.class), anyLong(), any())).thenAnswer((Answer<?>) invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        });
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenAnswer((Answer<?>) invocation -> {
                    Runnable runnable = invocation.getArgument(0);
                    runnable.run();
                    return null;
                });
        when(communicationServiceFactory.create(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(communicationService);
    }

    @Test
    public void testInitialize() throws Exception {
        // ARRANGE
        final String thingId = "LWZ_THZ504_7_59";
        final String configFile = String.format("%s.xml", thingId);
        final var thingUuidStr = String.format("stiebelheatpump:%s:StiebelHeatpumpThz504", thingId);

        // Channels MOCK
        List<Channel> channels = new ArrayList<>();
        TestUtils.getAvailableChannels().forEach(channelInfo -> {
            final String channelTypeId = String.format("stiebelheatpump:%s", channelInfo.getChannelTypeId());
            final String channelUuidStr = String.format("%s:%s#%s", thingUuidStr, "somegroup",
                    channelInfo.getChannelId());

            var channelUid = new ChannelUID(channelUuidStr);
            var channelType = new ChannelTypeUID(channelTypeId);
            var mockedChannel = mock(Channel.class, channelInfo.getChannelId());

            when(mockedChannel.getUID()).thenReturn(channelUid);
            lenient().when(mockedChannel.getChannelTypeUID()).thenReturn(channelType);
            lenient().when(mockedChannel.getAcceptedItemType()).thenReturn(channelInfo.getChannelItemType());

            when(thing.getChannel(channelInfo.getChannelId())).thenReturn(mockedChannel);
            lenient().when(thing.getChannel(channelInfo.getChannelId())).thenReturn(mockedChannel);
            lenient().when(thing.getChannel(channelUid)).thenReturn(mockedChannel);

            channels.add(mockedChannel);
        });

        // Channel id to mocked value mapping
        Map<String, Object> responses = Map.ofEntries(Map.entry("insideTemperatureRC", 22.7d),
                Map.entry("seasonMode", (short) 1), Map.entry("heatSetpointTemperatureHC1", 27.1d),
                Map.entry("extractFanSpeed", (short) 25), Map.entry("supplyFanSpeed", (short) 34),
                Map.entry("exhaustFanSpeed", (short) 0), Map.entry("BoosterHc", false), Map.entry("FilterDown", false),
                Map.entry("Cooling", false), Map.entry("PumpHc", true), Map.entry("Compressor", true),
                Map.entry("Service", false), Map.entry("FilterUp", false), Map.entry("FilterBoth", false),
                Map.entry("HeatingHc", false), Map.entry("VentStage", false), Map.entry("SwitchingProgram", true),
                Map.entry("HeatingDhw", true), Map.entry("Defrost", false), Map.entry("programDhwMo0Start", (short) 50),
                Map.entry("programDhwMo0End", (short) 69));
        when(communicationService.getRequestData(any())).thenReturn(responses);

        TestUtils.mockConfig(configFileLoader, configFile);

        // Thing MOCK
        final String port = "/dev/ttyUSB0";
        var config = new Configuration(Map.of("port", port, "refresh", 1000, "waitingTime", 1000, "baudRate", 9600));
        final var properties = Map.of(Thing.PROPERTY_FIRMWARE_VERSION, "7.59");
        var thingUuid = new ThingUID(thingUuidStr);
        when(thing.getUID()).thenReturn(thingUuid);
        when(thing.getThingTypeUID()).thenReturn(thingTypeUID);
        when(thingTypeUID.getId()).thenReturn(thingId);
        when(thing.getConfiguration()).thenReturn(config);
        when(thing.getProperties()).thenReturn(properties);
        when(thing.getChannels()).thenReturn(channels);

        when(communicationService.getVersion(any())).thenReturn("7.59");
        when(serialPortManager.getIdentifier(port)).thenReturn(serialPortIdentifier);

        // Uid String to State mapping
        var capturedStateUpdates = new HashMap<String, State>();
        when(thingHandlerCallback.isChannelLinked(any())).thenReturn(true);
        doAnswer(invocation -> {
            ChannelUID channelUid = invocation.getArgument(0);
            State state = invocation.getArgument(1);
            capturedStateUpdates.put(channelUid.getAsString(), state);
            return null;
        }).when(thingHandlerCallback).stateUpdated(any(), any());

        var handler = new StiebelHeatPumpHandler(thing, serialPortManager, configFileLoader,
                communicationServiceFactory, scheduler);
        handler.setCallback(thingHandlerCallback);

        // ACT
        handler.initialize();

        // ASSERT
        var expectedStateUpdates = Map.ofEntries(
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#insideTemperatureRC",
                        new QuantityType("22.7 °C")),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#seasonMode",
                        new DecimalType("1")),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#heatSetpointTemperatureHC1",
                        new QuantityType("27.1 °C")),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#extractFanSpeed",
                        new DecimalType("25")),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#supplyFanSpeed",
                        new DecimalType("34")),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#exhaustFanSpeed",
                        new DecimalType("0")),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#BoosterHc",
                        OpenClosedType.CLOSED),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#FilterDown",
                        OpenClosedType.CLOSED),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#Cooling",
                        OpenClosedType.CLOSED),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#PumpHc",
                        OpenClosedType.OPEN),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#Compressor",
                        OpenClosedType.OPEN),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#Service",
                        OpenClosedType.CLOSED),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#FilterUp",
                        OpenClosedType.CLOSED),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#FilterBoth",
                        OpenClosedType.CLOSED),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#HeatingHc",
                        OpenClosedType.CLOSED),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#VentStage",
                        OpenClosedType.CLOSED),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#SwitchingProgram",
                        OpenClosedType.OPEN),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#HeatingDhw",
                        OpenClosedType.OPEN),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#Defrost",
                        OpenClosedType.CLOSED),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwMo0Start",
                        new DateTimeType("1970-01-01T12:30:00.000")), // 50 / 4 = 12.5 -> 12:30
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwMo0End",
                        new DateTimeType("1970-01-01T17:15:00.000")) // 69 / 4 = 17.25 -> 17:15
        );

        assertEquals(expectedStateUpdates.size() + 1, capturedStateUpdates.size()); // Additional value: last update
                                                                                    // time
        capturedStateUpdates.forEach((channelUidStr, capturedState) -> {
            // Assert refreshTime channel individually
            if (channelUidStr
                    .equals("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:currentValues#refreshTime")) {
                var capturedRefreshTime = (StringType) capturedState;
                var capturedZonedDt = LocalDateTime
                        .parse(capturedRefreshTime.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.systemDefault());
                var now = ZonedDateTime.now();
                var diff = now.toEpochSecond() - capturedZonedDt.toEpochSecond();
                assertEquals(0, diff);
                return;
            }

            var expectedState = expectedStateUpdates.get(channelUidStr);
            assertEquals(expectedState, capturedState);
        });
    }
}
