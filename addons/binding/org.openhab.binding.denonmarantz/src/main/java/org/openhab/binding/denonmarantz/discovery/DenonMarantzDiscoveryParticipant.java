/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.denonmarantz.discovery;

import static org.openhab.binding.denonmarantz.DenonMarantzBindingConstants.THING_TYPE_AVR;

import java.net.Inet4Address;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.jmdns.ServiceInfo;

import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.io.transport.mdns.discovery.MDNSDiscoveryParticipant;
import org.openhab.binding.denonmarantz.DenonMarantzBindingConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Jan-Willem Veldhuis
 *
 */
public class DenonMarantzDiscoveryParticipant implements MDNSDiscoveryParticipant {

    private Logger logger = LoggerFactory.getLogger(DenonMarantzDiscoveryParticipant.class);

    // Service type for 'Airplay enabled' receivers
    private static final String RAOP_SERVICE_TYPE = "_raop._tcp.local.";

    /**
     * Match the serial number, vendor and model of the discovered AVR.
     * Input is like "0006781D58B1@Marantz SR5008._raop._tcp.local."
     * Allow Denon / Marantz only.
     */
    private static final Pattern DENON_MARANTZ_PATTERN = Pattern
            .compile("^([A-Z0-9]+)@(Denon|Marantz){1}([^\\.]+)(.+)$");

    private String vendor;

    private String model;

    private String serial;

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Collections.singleton(THING_TYPE_AVR);
    }

    @Override
    public String getServiceType() {
        return RAOP_SERVICE_TYPE;
    }

    @Override
    public DiscoveryResult createResult(ServiceInfo service) {
        logger.debug("AVR found: {}", service.getQualifiedName());
        ThingUID uid = getThingUID(service);
        if (uid != null) {
            Map<String, Object> properties = new HashMap<>(2);

            if (service.getInet4Addresses().length == 0) {
                logger.debug("Could not determine IP address for the Denon/Marantz AVR");
                return null;
            }
            Inet4Address host = service.getInet4Addresses()[0];
            logger.debug("IP Address: {}", host.getHostAddress());
            properties.put(DenonMarantzBindingConstants.PARAMETER_HOST, host.getHostAddress());
            properties.put(Thing.PROPERTY_SERIAL_NUMBER, this.serial);
            properties.put(Thing.PROPERTY_VENDOR, this.vendor);
            properties.put(Thing.PROPERTY_MODEL_ID, this.model);

            String label = this.vendor + ' ' + this.model;
            DiscoveryResult result = DiscoveryResultBuilder.create(uid).withProperties(properties).withLabel(label)
                    .withRepresentationProperty(Thing.PROPERTY_SERIAL_NUMBER).build();
            return result;

        } else {
            return null;
        }
    }

    @Override
    public ThingUID getThingUID(ServiceInfo service) {
        Matcher matcher = DENON_MARANTZ_PATTERN.matcher(service.getQualifiedName());
        if (matcher.matches()) {
            logger.debug("This seems like a supported Denon/Marantz AVR!");
            this.serial = matcher.group(1).toLowerCase();
            this.vendor = matcher.group(2);
            this.model = matcher.group(3).trim().replace(' ', '-');

            return new ThingUID(THING_TYPE_AVR, this.serial);

        } else {
            logger.debug("This discovered device is not supported by the DenonMarantz binding, ignoring..");
        }
        return null;
    }

}
