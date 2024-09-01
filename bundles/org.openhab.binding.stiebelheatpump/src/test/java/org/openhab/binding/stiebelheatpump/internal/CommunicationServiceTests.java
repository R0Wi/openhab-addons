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

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.openhab.binding.stiebelheatpump.protocol.DataParser;
import org.openhab.binding.stiebelheatpump.protocol.ProtocolConnector;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition;
import org.openhab.binding.stiebelheatpump.protocol.RecordDefinition.Type;
import org.openhab.binding.stiebelheatpump.protocol.Request;
import org.openhab.binding.stiebelheatpump.protocol.Requests;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.util.HexUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * @author Stefan Triller
 */
public class CommunicationServiceTests {

    SerialPortManager serialPortManager = mock(SerialPortManager.class);
    SerialPortIdentifier serialPortIdentifier = mock(SerialPortIdentifier.class);
    SerialPort serialPort = mock(SerialPort.class);

    InputStream inputStream = mock(InputStream.class);
    OutputStream outputStream = mock(OutputStream.class);

    @BeforeEach
    public void setup() throws Exception {
        SerialPort serialPort = mockSerialPort();
        serialPortManager = mockSerialPortManager(serialPort);
    }

    @Test
    public void test() throws Exception {
        mockSerialPort();

        CommunicationService cs = new CommunicationService(serialPortManager, "", 9600, 1000, null);
        // cs.setConnector(mockSerialConnector());
        // cs.connect();

        RecordDefinition updateRecord = new RecordDefinition("myChannel",
                new byte[] { (byte) 0x0a, (byte) 0x05, (byte) 0x6c }, 1, 1, 1, Type.Settings, 0, 3, 1, "unitTest");
        // cs.writeData(1, "myChannel", updateRecord);

        byte[] request = cs.createRequestMessage(updateRecord.getRequestByte());
        String req = HexUtils.bytesToHex(request);

        String correctAnswer = "01007C0A056C1003";

        assertEquals(req, correctAnswer);
    }

    @Test
    public void testParseLwzThz504() throws StiebelHeatPumpException, IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        URL congfigUrl = classLoader.getResource("HeatpumpConfig/LWZ_THZ504_7_59.xml");
        Bundle mockedBundle = mock(Bundle.class);
        when(mockedBundle.getEntry(anyString())).thenReturn(congfigUrl);
        try (MockedStatic<FrameworkUtil> mockedFrameworkUtil = Mockito.mockStatic(FrameworkUtil.class)) {
            mockedFrameworkUtil.when(() -> FrameworkUtil.getBundle(ConfigParser.class)).thenReturn(mockedBundle);
            ConfigLocator configLocator = new ConfigLocator("LWZ_THZ504_7_59.xml");
            List<Request> requests = configLocator.getRequests();
            // Value Response
            // String responseHexStr =
            // "010096fbfda8005301ad017b023001a78001fda800d50176100807012c012c02ee000d00190019006e014f000002f00a5e45393c4944e12734062500cc00290000017d019a008d009f0183000000000af6";

            // FB = Value Request, 09 = Static settings Request, 17 = temp settings Request, FD = Get Version, 0A0176 =
            // Get Display
            String requestString = "0A0176";
            String responseHexStr = "0100990a01760413";

            // Auto, Compressor, DWH, Heatpump => 1; Booster, Heating, Defrost => 0: 0100990a01760413

            // Auto, all others off: 0100830a01760001

            // all off: 0100820a01760000

            byte[] response = HexUtils.hexToBytes(responseHexStr);

            byte requestByte = HexUtils.hexToBytes(requestString)[0];
            Request request = requests.stream().filter(r -> r.getRequestByte()[0] == requestByte).findFirst().get();

            DataParser dataParser = new DataParser();
            Map<String, Object> parsedRecords = dataParser.parseRecords(response, request);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter("parsedRecords.txt"))) {
                // Print values
                parsedRecords.forEach((key, value) -> {
                    try {
                        writer.write(key + " = " + value + "\n");
                    } catch (IOException e) {
                        // e.printStackTrace();
                    }
                });
            }
        }
    }

    @Test
    public void testParseCoolingHc1SetThz504() throws StiebelHeatPumpException, IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        URL congfigUrl = classLoader.getResource("HeatpumpConfig/LWZ_THZ504_7_59.xml");
        Bundle mockedBundle = mock(Bundle.class);
        when(mockedBundle.getEntry(anyString())).thenReturn(congfigUrl);
        try (MockedStatic<FrameworkUtil> mockedFrameworkUtil = Mockito.mockStatic(FrameworkUtil.class)) {
            mockedFrameworkUtil.when(() -> FrameworkUtil.getBundle(ConfigParser.class)).thenReturn(mockedBundle);
            ConfigLocator configLocator = new ConfigLocator("LWZ_THZ504_7_59.xml");
            List<Request> requests = configLocator.getRequests();

            // Get Cooling HC1 Setting
            String requestString = "0B0287";
            String responseHexStr = "0100960B028700011003";

            byte[] response = HexUtils.hexToBytes(responseHexStr);

            byte requestByte = HexUtils.hexToBytes(requestString)[0];
            Request request = requests.stream().filter(r -> r.getRequestByte()[0] == requestByte).findFirst().get();

            DataParser dataParser = new DataParser();
            Map<String, Object> parsedRecords = dataParser.parseRecords(response, request);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter("parsedRecords.txt"))) {
                // Print values
                parsedRecords.forEach((key, value) -> {
                    try {
                        writer.write(key + " = " + value + "\n");
                    } catch (IOException e) {
                        // e.printStackTrace();
                    }
                });
            }
        }
    }

    @Test
    public void testSetCoolingThz504() throws Exception {
        final String channelId = "p99CoolingHC1Switch";
        mockSerialPort();

        // Mock scheduler service to just execute everything right away
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) {
                Runnable runnable = invocation.getArgument(0);
                runnable.run();
                return null;
            }
        });

        // Configure output stream to return data
        when(inputStream.read(any())).thenAnswer(new Answer<Object>() {
            private int count = 0;

            @Override
            public Object answer(InvocationOnMock invocation) {
                byte[] buffer = invocation.getArgument(0);
                if (count == 0) {
                    buffer[0] = DataParser.ESCAPE;
                } else if (count == 1) {
                    buffer[0] = DataParser.STARTCOMMUNICATION;
                } else {
                    buffer[0] = 0x00;
                }
                count++;
                return 1;
            }
        });
        when(inputStream.available()).thenReturn(1);

        CommunicationService communicationService = new CommunicationService(serialPortManager, "", 9600, 1000,
                scheduler);
        communicationService.connect();

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        URL congfigUrl = classLoader.getResource("HeatpumpConfig/LWZ_THZ504_7_59.xml");
        Bundle mockedBundle = mock(Bundle.class);
        when(mockedBundle.getEntry(anyString())).thenReturn(congfigUrl);
        try (MockedStatic<FrameworkUtil> mockedFrameworkUtil = Mockito.mockStatic(FrameworkUtil.class)) {
            mockedFrameworkUtil.when(() -> FrameworkUtil.getBundle(ConfigParser.class)).thenReturn(mockedBundle);

            ConfigLocator configLocator = new ConfigLocator("LWZ_THZ504_7_59.xml");
            List<Request> requestList = configLocator.getRequests();
            Requests requests = new Requests(requestList);
            RecordDefinition coolingSwitchRecordDefinition = requests.getRecordDefinitionByChannelId(channelId);

            communicationService.writeData(true, channelId, coolingSwitchRecordDefinition);

        }
    }

    private SerialPort mockSerialPort() throws Exception {
        SerialPort serialPort = mock(SerialPort.class);
        when(serialPort.getOutputStream()).thenReturn(outputStream);
        when(serialPort.getInputStream()).thenReturn(inputStream);
        return serialPort;
    }

    private SerialPortManager mockSerialPortManager(SerialPort serialPort) throws Exception {
        SerialPortManager serialPortManager = mock(SerialPortManager.class);
        SerialPortIdentifier serialPortIdentifier = mock(SerialPortIdentifier.class);
        when(serialPortManager.getIdentifier(anyString())).thenReturn(serialPortIdentifier);
        when(serialPortIdentifier.open(anyString(), anyInt())).thenReturn(serialPort);
        return serialPortManager;
    }

    private ProtocolConnector mockSerialConnector() throws StiebelHeatPumpException {
        ProtocolConnector connector = mock(ProtocolConnector.class);
        when(connector.get()).thenAnswer(new Answer<Object>() {
            private int count = 0;

            @Override
            public Object answer(InvocationOnMock invocation) {
                // System.out.println("count = " + count);
                count++;
                if (count == 1) {
                    return DataParser.ESCAPE;
                } else if (count == 2) {
                    return DataParser.STARTCOMMUNICATION;
                } else {
                    return null;
                }
            }
        });

        return connector;
    }
}
