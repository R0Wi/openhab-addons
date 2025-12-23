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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Test Utilities.
 *
 * @author Robin Windey - Initial Contribution
 */
public class TestUtils {
    public static void mockConfig(ConfigFileLoader configFileLoader, String config) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        URL congfigUrl = classLoader.getResource("HeatpumpConfig/" + config);
        when(configFileLoader.getConfig(anyString())).thenReturn(congfigUrl);
    }

    public static Stream<ChannelInfo> getAvailableChannels() {
        var channelItemTypes = getChannelItemTypes();
        var channelGroupTypes = getXmlAttributes("OH-INF/thing/channelgroup-types.xml", "channel", "id", "typeId");
        return channelGroupTypes.map(entry -> {
            String channelId = entry.getKey();
            String channelTypeId = entry.getValue();
            String channelItemType = channelItemTypes.get(channelTypeId);
            return new ChannelInfo(channelId, channelTypeId, channelItemType);
        });
    }

    public static void prepareLogAppender(ListAppender<ILoggingEvent> logAppender) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logAppender.setContext(context);
        logAppender.setName("listAppender");
        logAppender.start();
        ch.qos.logback.classic.Logger lbLogger = context.getLogger("ROOT");
        lbLogger.addAppender(logAppender);
        lbLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    }

    private static Stream<Entry<String, String>> getXmlAttributes(String xmlFile, String tagName,
            String keyAttributeName, String valueAttributeName) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        URL xmlUrl = classLoader.getResource(xmlFile);
        try (InputStream inputStream = xmlUrl.openStream()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);
            NodeList channelGroupNodes = document.getElementsByTagName(tagName);

            return Stream.iterate(0, i -> i + 1).limit(channelGroupNodes.getLength())
                    .map(i -> channelGroupNodes.item(i))
                    .map(node -> Map.entry(((Element) node).getAttribute(keyAttributeName),
                            ((Element) node).getAttribute(valueAttributeName)));

        } catch (Exception e) {
            throw new RuntimeException("Error reading XML file", e);
        }
    }

    private static Map<String, String> getChannelItemTypes() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        URL xmlUrl = classLoader.getResource("OH-INF/thing/channel-types.xml");
        try (InputStream inputStream = xmlUrl.openStream()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);
            NodeList channelTypes = document.getElementsByTagName("channel-type");
            Map<String, String> result = new HashMap<>();
            for (int i = 0; i < channelTypes.getLength(); i++) {
                Element element = (Element) channelTypes.item(i);
                String id = element.getAttribute("id");
                String itemType = element.getElementsByTagName("item-type").item(0).getTextContent();
                result.put(id, itemType);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Error reading XML file", e);
        }
    }
}
