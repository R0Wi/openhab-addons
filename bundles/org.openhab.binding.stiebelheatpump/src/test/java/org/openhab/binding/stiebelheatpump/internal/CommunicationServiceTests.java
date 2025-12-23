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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.openhab.binding.stiebelheatpump.exception.StiebelHeatPumpException;
import org.openhab.binding.stiebelheatpump.protocol.DataParser;
import org.openhab.binding.stiebelheatpump.protocol.ProtocolConnector;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.Request;
import org.openhab.binding.stiebelheatpump.protocol.Requests;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.util.HexUtils;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * @author Stefan Triller
 * @author Robin Windey
 */
@ExtendWith(MockitoExtension.class)
public class CommunicationServiceTests {

    private @Mock SerialPortManager serialPortManager;
    private @Mock ProtocolConnector connector;
    private @Mock ConfigFileLoader configFileLoader;
    private final List<Byte> writtenBytesBuffer = new ArrayList<Byte>();
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<ILoggingEvent>();

    @BeforeEach
    public void setUp() {
        TestUtils.prepareLogAppender(logAppender);
    }

    @AfterEach
    public void tearDown() {
        logAppender.stop();
    }

    @Test
    public void testSetCoolingThz504() throws Exception {
        final String channelId = "p99CoolingHC1Switch"; // Usually channelId will also contain group
        final String config = "LWZ_THZ504_7_59.xml";

        TestUtils.mockConfig(configFileLoader, config);

        mockWriteBuffer();

        final String coolingResponseCurrent = "0100960B028700001003"; // Cooling currently set to "off"
        final String coolingSetOk = "01808C0B1003"; // Machine "OK" response

        setMockedMachineResponse(
                // Communication for "get current value"
                new byte[] { DataParser.ESCAPE }, DataParser.DATAAVAILABLE, HexUtils.hexToBytes(coolingResponseCurrent),
                // Response after new value has been set
                new byte[] { DataParser.ESCAPE }, DataParser.DATAAVAILABLE, HexUtils.hexToBytes(coolingSetOk));

        try (CommunicationServiceImpl communicationService = new CommunicationServiceImpl(serialPortManager, "", 9600,
                1, connector)) {
            communicationService.connect();

            ConfigLocator configLocator = new ConfigLocator(config, configFileLoader);
            List<Request> requestList = configLocator.getRequests();
            Requests requests = new Requests();
            requests.setRequests(requestList);
            RecordDefinition coolingSwitchRecordDefinition = requests.getRecordDefinitionByChannelId(channelId);

            var newData = communicationService.writeData(true, coolingSwitchRecordDefinition);
            assertTrue(newData.keySet().size() == 1);
            assertEquals(true, newData.get(channelId));
        }

        byte[] writtenBytes = writtenBytesBuffer.stream()
                .collect(ByteArrayOutputStream::new, (baos, b) -> baos.write(b), (baos1, baos2) -> {
                }).toByteArray();
        String writtenBytesHex = HexUtils.bytesToHex(writtenBytes);

        // HEADER + GET/SET + CHECKSUM + COMMAND + (VALUE) + FOOTER
        final String getValueHex = "01 00 95 0B0287      1003".replaceAll("\\s", ""); // GET command
        final String setValueHex = "01 80 16 0B0287 0001 1003".replaceAll("\\s", ""); // SET command

        final String escape = "10";
        final String startCommunication = "02";
        final String expected = startCommunication + getValueHex + escape + startCommunication + setValueHex + escape;

        assertEquals(expected, writtenBytesHex);
    }

    @ParameterizedTest
    @MethodSource("provideArgumentsForInvalidSetResponse")
    public void testSetCoolingThz504Fails(final String setFailedResponse, final String errorMsgSnippet)
            throws Exception {
        final String channelId = "p99CoolingHC1Switch";
        final String config = "LWZ_THZ504_7_59.xml";

        TestUtils.mockConfig(configFileLoader, config);

        mockWriteBuffer();

        final String coolingResponseCurrent = "0100960B028700001003"; // Cooling currently set to "off"

        setMockedMachineResponse(
                // Communication for "get current value"
                new byte[] { DataParser.ESCAPE }, DataParser.DATAAVAILABLE, HexUtils.hexToBytes(coolingResponseCurrent),
                // Response after new value has been set
                new byte[] { DataParser.ESCAPE }, DataParser.DATAAVAILABLE, HexUtils.hexToBytes(setFailedResponse));

        try (CommunicationServiceImpl communicationService = new CommunicationServiceImpl(serialPortManager, "", 9600,
                1, connector)) {
            communicationService.connect();

            ConfigLocator configLocator = new ConfigLocator(config, configFileLoader);
            List<Request> requestList = configLocator.getRequests();
            Requests requests = new Requests();
            requests.setRequests(requestList);
            RecordDefinition coolingSwitchRecordDefinition = requests.getRecordDefinitionByChannelId(channelId);

            var newData = communicationService.writeData(true, coolingSwitchRecordDefinition);

            assertTrue(newData.keySet().size() == 1);
            assertEquals(false, newData.get(channelId));

            var logWarning = logAppender.list.stream()
                    .filter(entry -> entry.getLevel().equals(ch.qos.logback.classic.Level.WARN))
                    .filter(entry -> entry.getMessage().equals("Verification of header for set operation failed"))
                    .findFirst().orElse(null);
            assertNotNull(logWarning);
            // Verify nested exception message which should have been logged
            assertTrue(logWarning.getThrowableProxy().getMessage().contains(errorMsgSnippet));
        }
    }

    @Test
    public void testSetDhwMoSlot1() throws Exception {
        final String channelIdStart = "programDhwMo1Start";
        final String channelIdEnd = "programDhwMo1End";
        final String config = "LWZ_THZ504_7_59.xml";

        TestUtils.mockConfig(configFileLoader, config);

        mockWriteBuffer();

        final String moSlot1Response = "0100330A171180801003"; // Both channels currently set to "unset"
        final String moSlot1ResponseAfterSet = "0100A40A17112D441003";

        setMockedMachineResponse(
                // Communication for "get current value"
                new byte[] { DataParser.ESCAPE }, DataParser.DATAAVAILABLE, HexUtils.hexToBytes(moSlot1Response),
                // Communictaion after new value has been set
                new byte[] { DataParser.ESCAPE }, DataParser.DATAAVAILABLE,
                HexUtils.hexToBytes(moSlot1ResponseAfterSet));

        try (CommunicationServiceImpl communicationService = new CommunicationServiceImpl(serialPortManager, "", 9600,
                1, connector)) {

            communicationService.connect();

            ConfigLocator configLocator = new ConfigLocator(config, configFileLoader);
            List<Request> requestList = configLocator.getRequests();
            Requests requests = new Requests();
            requests.setRequests(requestList);

            Short start = 45; // 49/4 = 12.5 => 12:30
            Short end = 68; // 69/4 = 17.25 => 17:00
            RecordDefinition startChannelDefinition = requests.getRecordDefinitionByChannelId(channelIdStart);
            RecordDefinition endChannelDefinition = requests.getRecordDefinitionByChannelId(channelIdEnd);

            var newData = communicationService.writeTimeQuaterPair(start, end, startChannelDefinition,
                    endChannelDefinition);

            assertEquals(start, newData.get(channelIdStart));
            assertEquals(end, newData.get(channelIdEnd));
        }

        byte[] writtenBytes = writtenBytesBuffer.stream()
                .collect(ByteArrayOutputStream::new, (baos, b) -> baos.write(b), (baos1, baos2) -> {
                }).toByteArray();
        String writtenBytesHex = HexUtils.bytesToHex(writtenBytes);

        // // HEADER + GET/SET + CHECKSUM + COMMAND + (VALUE) + FOOTER
        final String getValueHex = "0100 33 0A17 11       1003".replaceAll("\\s", ""); // GET command
        final String setValueHex = "0180 24 0A17 11 2D 44 1003".replaceAll("\\s", ""); // SET command

        final String escape = "10";
        final String startCommunication = "02";
        final String expected = startCommunication + getValueHex + escape + startCommunication + setValueHex + escape;

        assertEquals(expected, writtenBytesHex);
    }

    @ParameterizedTest
    @MethodSource("provideArgumentsForGetValuesThz504Test")
    public void testGetValuesThz504(String request, String response1, String response2, Map<String, Object> expected)
            throws Exception {
        final String config = "LWZ_THZ504_7_59.xml";
        TestUtils.mockConfig(configFileLoader, config);

        final byte[] response1Bytes = HexUtils.hexToBytes(response1);
        final byte[] response2Bytes = response2 != null ? HexUtils.hexToBytes(response2) : new byte[0];
        final byte[] initialHandshake = { DataParser.ESCAPE };
        final byte[] handshake2 = response2 != null ? initialHandshake : new byte[0];

        setMockedMachineResponse(initialHandshake, DataParser.DATAAVAILABLE, response1Bytes, handshake2,
                DataParser.DATAAVAILABLE, response2Bytes);

        try (CommunicationServiceImpl communicationService = new CommunicationServiceImpl(serialPortManager, "", 9600,
                1, connector)) {
            communicationService.connect();

            var requestObj = findMatchingRequest(config, request);
            var result = communicationService.getRequestData(List.of(requestObj), null);
            for (String key : expected.keySet()) {
                assertEquals(expected.get(key), result.get(key));
            }
        }

        verify(connector).disconnect();
    }

    /**
     * Finds the request object that matches the given request hex string.
     * Needs a mocked config file loader to work.
     * 
     * @param config
     * @param request
     * @return
     */
    private Request findMatchingRequest(String config, String request) {
        ConfigLocator configLocator = new ConfigLocator(config, configFileLoader);
        List<Request> requests = configLocator.getRequests();
        byte[] requestBytes = HexUtils.hexToBytes(request);
        return requests.stream().filter(r -> Arrays.equals(r.getRequestByte(), requestBytes)).findFirst().get();
    }

    /**
     * Simulates a stream of responses from the heat pump.
     * 
     * @param mockedMachineResponses
     * @throws StiebelHeatPumpException
     */
    private void setMockedMachineResponse(byte[]... mockedMachineResponses) throws StiebelHeatPumpException {
        byte[] mockedMachineResponse = Stream.of(mockedMachineResponses).collect(ByteArrayOutputStream::new,
                (result, element) -> result.write(element, 0, element.length), (a, b) -> {
                }).toByteArray();
        when(connector.get()).thenAnswer(new Answer<Object>() {
            private int count = 0;

            @Override
            public Object answer(InvocationOnMock invocation) {
                return mockedMachineResponse[count++];
            }
        });
    }

    /**
     * Combinations of request and expected responses for the testGetValuesThz504 test.
     * 
     * @return Stream of arguments for the testGetValuesThz504 test.
     */
    private static Stream<Arguments> provideArgumentsForGetValuesThz504Test() {
        var expectedDisplayResponse = Map.ofEntries(Map.entry("BoosterHc", false), Map.entry("FilterDown", false),
                Map.entry("Cooling", false), Map.entry("PumpHc", true), Map.entry("Compressor", true),
                Map.entry("Service", false), Map.entry("FilterUp", false), Map.entry("FilterBoth", false),
                Map.entry("HeatingHc", false), Map.entry("VentStage", false), Map.entry("SwitchingProgram", true),
                Map.entry("HeatingDhw", true), Map.entry("Defrost", false));
        var expectedFbResponse = Map.ofEntries(Map.entry("extractFanSpeed", (short) 25),
                Map.entry("supplyFanSpeed", (short) 34), Map.entry("exhaustFanSpeed", (short) 0));
        var expectedF4Response = Map.ofEntries(Map.entry("insideTemperatureRC", 22.7d),
                Map.entry("seasonMode", (short) 1), Map.entry("heatSetpointTemperatureHC1", 27.1d));
        var expectedMoSlot1Response = Map.ofEntries(Map.entry("programDhwMo0Start", (short) 49),
                Map.entry("programDhwMo0End", (short) 69));
        var expectedMoSlot2Response = Map.ofEntries(
                Map.entry("programDhwMo1Start", StiebelHeatPumpBindingConstants.RESET_TIME_QUATER),
                Map.entry("programDhwMo1End", StiebelHeatPumpBindingConstants.RESET_TIME_QUATER));

        var fbHexResponse = "01006AFBFDA8FFF4019B018C027602048001FDA800C401A9600807012C012C0000001900220000FFF5010F0000033F08E10000000000000000055400BE00000000019301A201D90196017A0000000007271003";
        var f4HexResponse = "01005AF400810000011400000119010F011500000101600800640100000000D40000000000E30200000000071003";
        var moSlot1Response = "0100A80A171031451003"; // Start = 0x31 = 49 (49/4 = 12.25 => 12:15), End = 0x45 = 69
                                                      // (69/4 = 17.25 => 17:15)
        var moSlot2Response = "0100330A171180801003"; // Both Start and End set to 0x80 = 128 => Unset

        // String request, String response1, String response2, Map<String, Object> expected
        return Stream.of(
                Arguments.of("0A091A", "01002E0A091A01FF1003", "0100300A091B00011003",
                        Map.of("electrDHWDay", (short) 1511)),
                Arguments.of("0A091E", "0100770A091E00451003", "01003A0A091F00071003",
                        Map.of("electrHCDay", (short) 7069)),
                Arguments.of("F4", f4HexResponse, null, expectedF4Response),
                Arguments.of("0B0287", "0100960B028700011003", null, Map.of("p99CoolingHC1Switch", true)),
                Arguments.of("0A0176", "0100990a01760413", null, expectedDisplayResponse),
                Arguments.of("FB", fbHexResponse, null, expectedFbResponse),
                Arguments.of("0A1710", moSlot1Response, null, expectedMoSlot1Response),
                Arguments.of("0A1711", moSlot2Response, null, expectedMoSlot2Response));
    }

    private static Stream<Arguments> provideArgumentsForInvalidSetResponse() {
        return Stream.of(Arguments.of("01018C0B1003", "timing issue"),
                Arguments.of("01028D0B1003", "CRC error in request"), Arguments.of("01038E0B1003", "unknown command"),
                Arguments.of("01048F0B1003", "UNKNOWN Register REQUEST"));
    }

    private void mockWriteBuffer() throws StiebelHeatPumpException {
        doAnswer(invocation -> {
            byte[] bytes = invocation.getArgument(0);
            for (byte b : bytes) {
                writtenBytesBuffer.add(b);
            }
            return null;
        }).when(connector).write(any(byte[].class));
        doAnswer(invocation -> {
            byte bt = invocation.getArgument(0);
            writtenBytesBuffer.add(bt);
            return null;
        }).when(connector).write(any(byte.class));
    }
}
