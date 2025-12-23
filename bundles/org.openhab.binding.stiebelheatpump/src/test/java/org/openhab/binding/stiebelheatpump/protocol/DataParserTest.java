/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.binding.stiebelheatpump.exception.InvalidDataException;
import org.openhab.binding.stiebelheatpump.internal.ConfigFileLoader;
import org.openhab.binding.stiebelheatpump.internal.ConfigLocator;
import org.openhab.binding.stiebelheatpump.internal.TestUtils;

/**
 * @author Robin Windey - Initial contribution
 */
@ExtendWith(MockitoExtension.class)
public class DataParserTest {

    @Mock
    private ConfigFileLoader configFileLoader;

    private Requests requests = new Requests();
    private DataParser dataParser;

    @BeforeEach
    public void setUp() {
        dataParser = new DataParser();

        final String config = "LWZ_THZ504_7_59.xml";

        TestUtils.mockConfig(configFileLoader, config);
        ConfigLocator configLocator = new ConfigLocator(config, configFileLoader);
        List<Request> requestList = configLocator.getRequests();

        requests.setRequests(requestList);
    }

    @Test
    public void testParseRecordThrowsInvalidDataException() {
        byte[] response = new byte[] { 0x01, (byte) 0x80, (byte) 0x8C, 0x0B, 0x10, 0x03 };
        RecordDefinition recordDefinition = requests.getRecordDefinitionByChannelId("p99CoolingHC1Switch");

        var ex = assertThrows(InvalidDataException.class, () -> dataParser.parseRecord(response, recordDefinition));
        var cause = ex.getCause();
        assertNotNull(cause);
        assertEquals(cause.getMessage(),
                "Response (00)01 80 8C 0B (04)10 03  does not have a valid length of at least 8 bytes");
    }
}
