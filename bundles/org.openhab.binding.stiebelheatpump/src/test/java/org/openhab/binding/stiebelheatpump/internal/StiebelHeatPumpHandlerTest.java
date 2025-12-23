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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;

import javax.measure.quantity.Temperature;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.openhab.binding.stiebelheatpump.exception.StiebelHeatPumpException;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.items.Item;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Test for StiebelHeatPumpHandler class.
 *
 * @author Robin Windey - Initial Contribution
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class StiebelHeatPumpHandlerTest {
    private static final String PORT = "/dev/ttyUSB0";

    private @Mock Thing thing;
    private @Mock ThingTypeUID thingTypeUID;
    private @Mock SerialPortManager serialPortManager;
    private @Mock SerialPortIdentifier serialPortIdentifier;
    private @Mock ConfigFileLoader configFileLoader;
    private @Mock CommunicationServiceFactory communicationServiceFactory;
    private @Mock CommunicationService communicationService;
    private @Mock ScheduledExecutorService scheduler;
    private @Mock ThingHandlerCallback thingHandlerCallback;
    private @Mock ItemChannelLinkRegistry itemChannelLinkRegistry;
    private List<Channel> channels = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<ILoggingEvent>();

    @BeforeEach
    public void setUp() throws StiebelHeatPumpException {
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

        // Mocking of thing, channels and config
        final String thingId = "LWZ_THZ504_7_59";
        final String configFile = String.format("%s.xml", thingId);
        final var thingUuidStr = String.format("stiebelheatpump:%s:StiebelHeatpumpThz504", thingId);

        // Channels MOCK
        TestUtils.getAvailableChannels().forEach(channelInfo -> {
            final String channelTypeId = String.format("stiebelheatpump:%s", channelInfo.getChannelTypeId());
            final String channelUuidStr = String.format("%s:%s#%s", thingUuidStr, "somegroup",
                    channelInfo.getChannelId());

            var channelUid = new ChannelUID(channelUuidStr);
            var channelType = new ChannelTypeUID(channelTypeId);
            var mockedChannel = mock(Channel.class, channelInfo.getChannelId());

            when(mockedChannel.getUID()).thenReturn(channelUid);
            when(mockedChannel.getChannelTypeUID()).thenReturn(channelType);
            when(mockedChannel.getAcceptedItemType()).thenReturn(channelInfo.getChannelItemType());

            when(thing.getChannel(channelInfo.getChannelId())).thenReturn(mockedChannel);
            when(thing.getChannel(channelUid)).thenReturn(mockedChannel);
            when(thing.getChannel(String.format("%s#%s", "somegroup", channelInfo.getChannelId())))
                    .thenReturn(mockedChannel);

            channels.add(mockedChannel);
        });

        // Thing and Config MOCK
        var config = new Configuration(Map.of("port", PORT, "refresh", 1000, "waitingTime", 1, "baudRate", 9600));
        final var properties = Map.of(Thing.PROPERTY_FIRMWARE_VERSION, "7.59");
        var thingUuid = new ThingUID(thingUuidStr);
        when(thing.getUID()).thenReturn(thingUuid);
        when(thing.getThingTypeUID()).thenReturn(thingTypeUID);
        when(thingTypeUID.getId()).thenReturn(thingId);
        when(thing.getConfiguration()).thenReturn(config);
        when(thing.getProperties()).thenReturn(properties);
        when(thing.getChannels()).thenReturn(channels);
        when(communicationService.getVersion(any())).thenReturn("7.59");
        when(serialPortManager.getIdentifier(PORT)).thenReturn(serialPortIdentifier);

        // Config file MOCK
        TestUtils.mockConfig(configFileLoader, configFile);

        // Capture logs
        TestUtils.prepareLogAppender(logAppender);

        // Timezone for timestamps
        System.setProperty("user.timezone", "Europe/Berlin");
    }

    @AfterEach
    public void tearDown() {
        logAppender.list.clear();
    }

    @Test
    public void testInitialize() throws Exception {
        // ARRANGE

        // Channel id to mocked value mapping
        Map<String, Object> responses = Map.ofEntries(Map.entry("insideTemperatureRC", 22.7d),
                Map.entry("seasonMode", (short) 1), Map.entry("heatSetpointTemperatureHC1", 27.1d),
                Map.entry("extractFanSpeed", (short) 25), Map.entry("supplyFanSpeed", (short) 34),
                Map.entry("exhaustFanSpeed", (short) 0), Map.entry("BoosterHc", false), Map.entry("FilterDown", false),
                Map.entry("Cooling", false), Map.entry("PumpHc", true), Map.entry("Compressor", true),
                Map.entry("Service", false), Map.entry("FilterUp", false), Map.entry("FilterBoth", false),
                Map.entry("HeatingHc", false), Map.entry("VentStage", false), Map.entry("SwitchingProgram", true),
                Map.entry("HeatingDhw", true), Map.entry("Defrost", false), Map.entry("programDhwMo0Start", (short) 50),
                Map.entry("programDhwMo0End", (short) 69),
                Map.entry("programDhwMo1Start", StiebelHeatPumpBindingConstants.RESET_TIME_QUATER),
                Map.entry("programDhwMo1End", StiebelHeatPumpBindingConstants.RESET_TIME_QUATER),
                Map.entry("programDhwMo2Start", (short) 100), // Invalid value
                Map.entry("programDhwMo2End", (short) -10)); // Invalid value
        when(communicationService.getRequestData(any(), any())).thenReturn(responses);

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
                communicationServiceFactory, itemChannelLinkRegistry, scheduler);
        handler.setCallback(thingHandlerCallback);

        // ACT
        handler.initialize();

        // ASSERT
        var expectedStateUpdates = Map.ofEntries(
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#insideTemperatureRC",
                        new QuantityType<Temperature>("22.7 °C")),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#seasonMode",
                        new DecimalType("1")),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#heatSetpointTemperatureHC1",
                        new QuantityType<Temperature>("27.1 °C")),
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
                        new StringType("1970-01-01T12:30:00.000+0100")), // 50 / 4 = 12.5 -> 12:30
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwMo0End",
                        new StringType("1970-01-01T17:15:00.000+0100")), // 69 / 4 = 17.25 -> 17:15
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwMo1Start",
                        UnDefType.NULL),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwMo1End",
                        UnDefType.NULL),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwMo2Start",
                        UnDefType.NULL),
                Map.entry("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwMo2End",
                        UnDefType.NULL));

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

    @ParameterizedTest
    @MethodSource("provideArgumentsForSetMondayDhwSlot0StartTo1215")
    public void testHandleCommandSetMondayDhwSlot0StartTo1215(final String time) throws StiebelHeatPumpException {
        // ARRANGE
        final Short expectedTimeValueStart = (short) 49; // 12:15
        final Short expectedTimeValueEnd = (short) 71; // 17:45
        var handler = new StiebelHeatPumpHandler(thing, serialPortManager, configFileLoader,
                communicationServiceFactory, itemChannelLinkRegistry, scheduler);
        handler.setCallback(thingHandlerCallback);
        // Initialize to set communicationService
        handler.initialize();

        var dtCommand = new StringType(time);
        var moStartSlot = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwMo0Start");

        // Simulate end item is already set to 17:45
        var moEndSlotItem = mock(Item.class);
        var moEndSlot = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwMo0End");
        when(itemChannelLinkRegistry.getLinkedItems(eq(moEndSlot))).thenReturn(Set.of(moEndSlotItem));
        when(moEndSlotItem.getState()).thenReturn(new StringType("1970-01-01T17:45:00.000+0100"));

        when(communicationService.writeTimeQuaterPair(any(), any(), any(), any()))
                .thenReturn(Map.ofEntries(Map.entry("somegroup#programDhwMo0Start", expectedTimeValueStart),
                        Map.entry("somegroup#programDhwMo0End", expectedTimeValueEnd)));

        // ACT
        handler.handleCommand(moStartSlot, dtCommand);

        // ASSERT
        verify(communicationService, times(1)).writeTimeQuaterPair(eq(expectedTimeValueStart), eq(expectedTimeValueEnd),
                argThat(rd -> rd.getChannelid().equals("somegroup#programDhwMo0Start")),
                argThat(rd -> rd.getChannelid().equals("somegroup#programDhwMo0End")));
        verify(thingHandlerCallback, times(1)).stateUpdated(eq(moStartSlot),
                eq(new StringType("1970-01-01T12:15:00.000+0100")));
        verify(thingHandlerCallback, times(1)).stateUpdated(eq(moEndSlot),
                eq(new StringType("1970-01-01T17:45:00.000+0100")));
        verify(itemChannelLinkRegistry, times(1)).getLinkedItems(eq(moEndSlot));
    }

    @Test
    public void testHandleCommandSetFridayDhwSlot0EndTo2000() throws StiebelHeatPumpException {
        // ARRANGE
        final Short expectedTimeValueStart = (short) 48; // 12:00
        final Short expectedTimeValueEnd = (short) 80; // 20:00
        var handler = new StiebelHeatPumpHandler(thing, serialPortManager, configFileLoader,
                communicationServiceFactory, itemChannelLinkRegistry, scheduler);
        handler.setCallback(thingHandlerCallback);
        // Initialize to set communicationService
        handler.initialize();

        var dtCommand = new StringType("20:00");
        var endSlot = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwFr0End");

        // Simulate start item is already set to 12:00
        var startSlotItem = mock(Item.class);
        var startSlot = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwFr0Start");
        when(itemChannelLinkRegistry.getLinkedItems(eq(startSlot))).thenReturn(Set.of(startSlotItem));
        when(startSlotItem.getState()).thenReturn(new StringType("1970-01-01T12:00:00.000+0100"));

        when(communicationService.writeTimeQuaterPair(any(), any(), any(), any()))
                .thenReturn(Map.ofEntries(Map.entry("somegroup#programDhwFr0Start", expectedTimeValueStart),
                        Map.entry("somegroup#programDhwFr0End", expectedTimeValueEnd)));

        // ACT
        handler.handleCommand(endSlot, dtCommand);

        // ASSERT
        verify(communicationService, times(1)).writeTimeQuaterPair(eq(expectedTimeValueStart), eq(expectedTimeValueEnd),
                argThat(rd -> rd.getChannelid().equals("somegroup#programDhwFr0Start")),
                argThat(rd -> rd.getChannelid().equals("somegroup#programDhwFr0End")));
        verify(thingHandlerCallback, times(1)).stateUpdated(eq(startSlot),
                eq(new StringType("1970-01-01T12:00:00.000+0100")));
        verify(thingHandlerCallback, times(1)).stateUpdated(eq(endSlot),
                eq(new StringType("1970-01-01T20:00:00.000+0100")));
        verify(itemChannelLinkRegistry, times(1)).getLinkedItems(eq(startSlot));
    }

    @ParameterizedTest
    @MethodSource("provideArgumentsForUnsetItemState")
    public void testHandleCommandResetSuDhwSlot0End(final StringType resetCommand) throws StiebelHeatPumpException {
        // ARRANGE
        var handler = new StiebelHeatPumpHandler(thing, serialPortManager, configFileLoader,
                communicationServiceFactory, itemChannelLinkRegistry, scheduler);
        handler.setCallback(thingHandlerCallback);
        // Initialize to set communicationService
        handler.initialize();

        var suStartSlot = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwSu0Start");
        var suStartSlotIdStr = suStartSlot.getId();
        var suEndSlot = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwSu0End");
        var suEndSlotIdStr = suEndSlot.getId();

        when(communicationService.writeTimeQuaterPair(any(), any(), any(), any())).thenReturn(Map.ofEntries(
                Map.entry("somegroup#programDhwSu0Start", StiebelHeatPumpBindingConstants.RESET_TIME_QUATER),
                Map.entry("somegroup#programDhwSu0End", StiebelHeatPumpBindingConstants.RESET_TIME_QUATER)));

        // ACT
        handler.handleCommand(suEndSlot, resetCommand); // Resetting one slot should reset whole pair

        // ASSERT
        verify(communicationService, times(1)).writeTimeQuaterPair(
                eq(StiebelHeatPumpBindingConstants.RESET_TIME_QUATER),
                eq(StiebelHeatPumpBindingConstants.RESET_TIME_QUATER),
                argThat(rd -> rd.getChannelid().equals(suStartSlotIdStr)),
                argThat(rd -> rd.getChannelid().equals(suEndSlotIdStr)));
        verify(thingHandlerCallback, times(1)).stateUpdated(eq(suStartSlot), eq(UnDefType.NULL));
        verify(thingHandlerCallback, times(1)).stateUpdated(eq(suEndSlot), eq(UnDefType.NULL));
        verifyNoInteractions(itemChannelLinkRegistry);
    }

    @ParameterizedTest
    @MethodSource("provideArgumentsForUnsetItemState")
    public void testHandleCommandSetWeDhwSlot0StartSetsEndToSameValueIfEndIsNotSet(final StringType unsetItemState)
            throws StiebelHeatPumpException {
        // ARRANGE
        final Short expectedTimeValue = (short) 26; // 06:30
        var handler = new StiebelHeatPumpHandler(thing, serialPortManager, configFileLoader,
                communicationServiceFactory, itemChannelLinkRegistry, scheduler);
        handler.setCallback(thingHandlerCallback);
        // Initialize to set communicationService
        handler.initialize();

        var dtCommand = new StringType("1970-01-01T06:30:00.000+0100");
        var moStartSlot = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwWe0Start");

        // Simulate end item is uninitialized
        var moEndSlotItem = mock(Item.class);
        var moEndSlot = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwWe0End");
        when(itemChannelLinkRegistry.getLinkedItems(eq(moEndSlot))).thenReturn(Set.of(moEndSlotItem));
        when(moEndSlotItem.getState()).thenReturn(unsetItemState);

        when(communicationService.writeTimeQuaterPair(any(), any(), any(), any()))
                .thenReturn(Map.ofEntries(Map.entry("somegroup#programDhwWe0Start", expectedTimeValue),
                        Map.entry("somegroup#programDhwWe0End", expectedTimeValue)));

        // ACT
        handler.handleCommand(moStartSlot, dtCommand);

        // ASSERT
        verify(communicationService, times(1)).writeTimeQuaterPair(eq(expectedTimeValue), eq(expectedTimeValue),
                argThat(rd -> rd.getChannelid().equals("somegroup#programDhwWe0Start")),
                argThat(rd -> rd.getChannelid().equals("somegroup#programDhwWe0End")));
        verify(thingHandlerCallback, times(1)).stateUpdated(eq(moStartSlot),
                eq(new StringType("1970-01-01T06:30:00.000+0100")));
        verify(thingHandlerCallback, times(1)).stateUpdated(eq(moEndSlot),
                eq(new StringType("1970-01-01T06:30:00.000+0100")));
        verify(itemChannelLinkRegistry, times(1)).getLinkedItems(eq(moEndSlot));
    }

    @ParameterizedTest
    @MethodSource("provideArgumentsForInvalidDate")
    public void testLogsWarningOnInvalidTimeQuaterValueAndDoesNotUpdateItem(final StringType dtCommand)
            throws StiebelHeatPumpException {
        // ARRANGE
        var handler = new StiebelHeatPumpHandler(thing, serialPortManager, configFileLoader,
                communicationServiceFactory, itemChannelLinkRegistry, scheduler);
        handler.setCallback(thingHandlerCallback);
        // Initialize to set communicationService
        handler.initialize();

        var endSlot = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwFr0End");

        // Simulate start item is already set to 12:00
        var startSlotItem = mock(Item.class);
        var startSlot = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwFr0Start");
        when(itemChannelLinkRegistry.getLinkedItems(eq(startSlot))).thenReturn(Set.of(startSlotItem));
        when(startSlotItem.getState()).thenReturn(new StringType("1970-01-01T12:00:00.000+0100"));
        var capturedStateUpdates = new HashMap<String, State>();
        doAnswer(invocation -> {
            ChannelUID channelUid = invocation.getArgument(0);
            State state = invocation.getArgument(1);
            capturedStateUpdates.put(channelUid.getAsString(), state);
            return null;
        }).when(thingHandlerCallback).stateUpdated(any(), any());

        // ACT
        handler.handleCommand(endSlot, dtCommand);

        // ASSERT
        verify(communicationService, times(1)).connect();
        verify(communicationService, times(1)).getVersion(any());
        verify(communicationService).setTime(any());
        verifyNoMoreInteractions(communicationService);
        // Except "last refresh", no values should be updated
        var nonTimeUpdateStates = capturedStateUpdates.entrySet().stream()
                .filter(entry -> !entry.getKey().contains("refreshTime")).toList();
        assertEquals(0, nonTimeUpdateStates.size());
        assertTrue(
                logAppender.list.stream().anyMatch(log -> log.getMessage().contains("Could not parse time quater value")
                        && log.getLevel().equals(ch.qos.logback.classic.Level.WARN)));
    }

    @Test
    public void testDoesNothingIfCommunicationServiceIsNotInitialized() throws StiebelHeatPumpException {
        // ARRANGE
        var handler = new StiebelHeatPumpHandler(thing, serialPortManager, configFileLoader,
                communicationServiceFactory, itemChannelLinkRegistry, scheduler);

        // ACT
        handler.handleCommand(
                new ChannelUID("stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#programDhwSu0End"),
                new StringType("somecommand"));

        // ASSERT
        verifyNoInteractions(communicationService);
        var logs = logAppender.list.stream()
                .filter(log -> log.getLoggerName().equals(StiebelHeatPumpHandler.class.getName())).toList();
        assertTrue(logs.stream().anyMatch(
                log -> log.getMessage().contains("Communication service is not initialized, cannot handle command.")
                        && log.getLevel().equals(ch.qos.logback.classic.Level.WARN)));
    }

    @Test
    public void testHandleCommandDelaysCommandsAccordingToConfig() throws InterruptedException {
        // ARRANGE
        final int waitTimeMs = 500; // Device can only handle requests every second ...
        var config = new Configuration(
                Map.of("port", PORT, "refresh", 1000, "waitingTime", waitTimeMs, "baudRate", 9600));
        when(thing.getConfiguration()).thenReturn(config);
        var capturedWriteCalls = new ArrayList<Long>();
        when(communicationService.writeData(any(), any())).thenAnswer(invocation -> {
            capturedWriteCalls.add(System.currentTimeMillis());
            return new HashMap<String, Object>();
        });
        var channelUID = new ChannelUID(
                "stiebelheatpump:LWZ_THZ504_7_59:StiebelHeatpumpThz504:somegroup#p99CoolingHC1Switch");
        var handler = new StiebelHeatPumpHandler(thing, serialPortManager, configFileLoader,
                communicationServiceFactory, itemChannelLinkRegistry, scheduler);
        handler.initialize();

        // ACT
        var thread1 = new Thread(() -> handler.handleCommand(channelUID, OnOffType.ON));
        var thread2 = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                fail("Thread interrupted", e);
            }
            handler.handleCommand(channelUID, OnOffType.OFF);
        });

        thread1.start();
        thread2.start();

        thread1.join();
        thread2.join();

        // ASSERT
        assertEquals(2, capturedWriteCalls.size());
        var diffBetweenCalls = Math.abs(capturedWriteCalls.get(1) - capturedWriteCalls.get(0));
        assertTrue(diffBetweenCalls >= waitTimeMs - 100); // Allow some buffer
    }

    private static Stream<Arguments> provideArgumentsForSetMondayDhwSlot0StartTo1215() {
        return Stream.of(Arguments.of("12:15"), Arguments.of("1970-01-01T12:15:00.000"),
                Arguments.of("1970-01-01T12:15:00.000+0100"), Arguments.of("1970-01-01T12:29:00.000")); // should be
                                                                                                        // rounded to
                                                                                                        // 12:15
    }

    private static Stream<Arguments> provideArgumentsForUnsetItemState() {
        return Stream.of(Arguments.of(new StringType()), Arguments.of(new StringType("")),
                Arguments.of(new StringType(null)), Arguments.of(new StringType(UnDefType.NULL.toString())),
                Arguments.of(new StringType(UnDefType.NULL.toString().toLowerCase())));
    }

    private static Stream<Arguments> provideArgumentsForInvalidDate() {
        return Stream.of(Arguments.of(new StringType("this is no date for sure")),
                Arguments.of(new StringType("nope")));
    }
}
