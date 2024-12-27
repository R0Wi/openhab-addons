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

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.openhab.binding.stiebelheatpump.protocol.DataParser;
import org.openhab.binding.stiebelheatpump.protocol.ProtocolConnector;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.Request;
import org.openhab.binding.stiebelheatpump.protocol.Requests;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.util.HexUtils;

/**
 * @author Stefan Triller
 * @author Robin Windey
 */
@ExtendWith(MockitoExtension.class)
public class CommunicationServiceTests {

    private @Mock SerialPortManager serialPortManager;
    private @Mock ProtocolConnector connector;
    private @Mock IConfigFileLoader configFileLoader;

    @Test
    public void testSetCoolingThz504() throws Exception {
        final String channelId = "p99CoolingHC1Switch";
        final String config = "LWZ_THZ504_7_59.xml";

        mockConfig(config);

        List<Byte> writtenBytesBuffer = new ArrayList<Byte>();
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

        final String coolingResponseCurrent = "0100960B028700001003"; // Cooling currently set to "off"
        final String coolingResponseNew = "0100960B028700011003"; // Cooling currently set to "on"

        setMockedMachineResponse(
                // Communication for "get current value"
                DataParser.DATAAVAILABLE, HexUtils.hexToBytes(coolingResponseCurrent),
                // Communictaion after new value has been set
                new byte[] { DataParser.ESCAPE }, DataParser.DATAAVAILABLE, HexUtils.hexToBytes(coolingResponseNew));

        CommunicationService communicationService = new CommunicationService(serialPortManager, "", 9600, 1000,
                connector);
        communicationService.connect();

        ConfigLocator configLocator = new ConfigLocator(config, configFileLoader);
        List<Request> requestList = configLocator.getRequests();
        Requests requests = new Requests();
        requests.setRequests(requestList);
        RecordDefinition coolingSwitchRecordDefinition = requests.getRecordDefinitionByChannelId(channelId);

        var newData = communicationService.writeData(true, channelId, coolingSwitchRecordDefinition);
        assertEquals(true, newData.get(channelId));

        byte[] writtenBytes = writtenBytesBuffer.stream()
                .collect(ByteArrayOutputStream::new, (baos, b) -> baos.write(b), (baos1, baos2) -> {
                }).toByteArray();
        String writtenBytesHex = HexUtils.bytesToHex(writtenBytes);

        // HEADER + GET/SET + CHECKSUM + COMMAND + (VALUE) + FOOTER
        final String getValueHex = "01 00 95 0B0287      1003".replaceAll("\\s", ""); // GET command
        final String setValueHex = "01 80 16 0B0287 0001 1003".replaceAll("\\s", ""); // SET command

        final String escape = "10";
        final String startCommunication = "02";
        final String expected = getValueHex + escape + startCommunication + setValueHex + escape;

        assertEquals(expected, writtenBytesHex);
    }

    @ParameterizedTest
    @MethodSource("provideArgumentsForGetValuesThz504Test")
    public void testGetValuesThz504(String request, String response1, String response2, Map<String, Object> expected)
            throws Exception {
        final String config = "LWZ_THZ504_7_59.xml";
        mockConfig(config);

        final byte[] response1Bytes = HexUtils.hexToBytes(response1);
        final byte[] response2Bytes = response2 != null ? HexUtils.hexToBytes(response2) : new byte[0];
        final byte[] initialHandshake = { DataParser.ESCAPE };

        setMockedMachineResponse(initialHandshake, DataParser.DATAAVAILABLE, response1Bytes, DataParser.DATAAVAILABLE,
                response2Bytes);

        CommunicationService communicationService = new CommunicationService(serialPortManager, "", 9600, 1000,
                connector);
        communicationService.connect();

        var requestObj = findMatchingRequest(config, request);
        var result = communicationService.readData(requestObj);
        for (String key : expected.keySet()) {
            assertEquals(expected.get(key), result.get(key));
        }
    }

    private void mockConfig(String config) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        URL congfigUrl = classLoader.getResource("HeatpumpConfig/" + config);
        when(configFileLoader.getConfig(anyString())).thenReturn(congfigUrl);
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

        return Stream.of(
                Arguments.of("0A091A", "01002E0A091A01FF1003", "0100300A091B00011003",
                        Map.of("electrDHWDay", (short) 1511)),
                Arguments.of("0A091E", "0100770A091E00451003", "01003A0A091F00071003",
                        Map.of("electrHCDay", (short) 7069)),
                Arguments.of("F4",
                        "01005AF400810000011400000119010F011500000101600800640100000000D40000000000E30200000000071003",
                        null, Map.of("insideTemperatureRC", 22.7d, "seasonMode", (short) 1)),
                Arguments.of("0B0287", "0100960B028700011003", null, Map.of("p99CoolingHC1Switch", true)),
                Arguments.of("0A0176", "0100990a01760413", null, expectedDisplayResponse));
    }
}
