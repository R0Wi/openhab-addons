/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
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
import org.openhab.core.thing.type.ThingType;
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

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = new HashSet<ThingTypeUID>() {
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

    private SerialPortManager serialPortManager;

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();
        ThingType thingType = getThingTypeByUID(thing.getThingTypeUID());

        if (supportsThingType(thingTypeUID)) {
            return new StiebelHeatPumpHandler(thing, thingType, serialPortManager);
        }

        return null;
    }

    @Reference
    protected void setSerialPortManager(final SerialPortManager serialPortManager) {
        this.serialPortManager = serialPortManager;
    }

    protected void unsetSerialPortManager(final SerialPortManager serialPortManager) {
        this.serialPortManager = null;
    }
}