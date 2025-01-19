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

import static org.openhab.binding.stiebelheatpump.internal.StiebelHeatPumpBindingConstants.*;

import java.util.HashSet;
import java.util.Set;

import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link StiebelHeatPumpHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Peter Kreutzer - Initial contribution
 */
@Component(configurationPid = "binding.stiebelheatpump", service = ThingHandlerFactory.class)
public class StiebelHeatPumpHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = new HashSet<>() {
        {
            add(THING_TYPE_LWZ206);
            add(THING_TYPE_LWZ236);
            add(THING_TYPE_LWZ419);
            add(THING_TYPE_LWZ509);
            add(THING_TYPE_LWZ539);
            add(THING_TYPE_LWZ739);
            add(THING_TYPE_LWZ759);
            add(THING_TYPE_THZ55_762);
        }
    };

    private final SerialPortManager serialPortManager;
    private final ItemChannelLinkRegistry itemChannelLinkRegistry;

    @Activate
    public StiebelHeatPumpHandlerFactory(@Reference final SerialPortManager serialPortManager,
            @Reference final ItemChannelLinkRegistry itemChannelLinkRegistry) {
        this.serialPortManager = serialPortManager;
        this.itemChannelLinkRegistry = itemChannelLinkRegistry;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (!supportsThingType(thingTypeUID)) {
            return null;
        }

        final CommunicationServiceFactory communicationServiceFactory = (serialPortManager, serialPortName, baudRate,
                waitingTime, connector) -> new CommunicationServiceImpl(serialPortManager, serialPortName, baudRate,
                        waitingTime, connector);
        final ConfigFileLoader configFileLoader = new ConfigFileLoaderImpl();

        return new StiebelHeatPumpHandler(thing, serialPortManager, configFileLoader, communicationServiceFactory,
                itemChannelLinkRegistry);
    }
}
