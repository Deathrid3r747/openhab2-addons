/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.denonmarantz;

import java.util.HashMap;

import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;

/**
 * The {@link DenonMarantzBinding} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Jan-Willem Veldhuis - Initial contribution
 */
public class DenonMarantzBindingConstants {

    public static final String BINDING_ID = "denonmarantz";

    // List of all Thing Type UIDs
    public final static ThingTypeUID THING_TYPE_AVR = new ThingTypeUID(BINDING_ID, "avr");

    // List of thing Parameters names
    public static final String PARAMETER_HOST = "host";
    public static final String PARAMETER_TELNET_ENABLED = "telnetEnabled";
    public static final String PARAMETER_TELNET_PORT = "telnetPort";
    public static final String PARAMETER_HTTP_PORT = "httpPort";
    public static final String PARAMETER_POLLING_INTERVAL = "httpPollingInterval";

    // List of all Channel ids
    public final static String CHANNEL_POWER = "power";
    public final static String CHANNEL_MAIN_ZONE_POWER = "mainZonePower";
    public final static String CHANNEL_MAIN_VOLUME = "mainVolume";
    public final static String CHANNEL_MUTE = "mute";
    public final static String CHANNEL_INPUT = "input";
    public final static String CHANNEL_SURROUND_PROGRAM = "surroundProgram";

    public final static String CHANNEL_NOW_PLAYING_ARTIST = "artist";
    public final static String CHANNEL_NOW_PLAYING_ALBUM = "album";
    public final static String CHANNEL_NOW_PLAYING_TRACK = "track";

    public static final String CHANNEL_ZONE2_POWER = "zone2Power";
    public static final String CHANNEL_ZONE2_VOLUME = "zone2Volume";
    public static final String CHANNEL_ZONE2_MUTE = "zone2Mute";
    public static final String CHANNEL_ZONE2_INPUT = "zone2Input";

    public static final String CHANNEL_ZONE3_POWER = "zone3Power";
    public static final String CHANNEL_ZONE3_VOLUME = "zone3Volume";
    public static final String CHANNEL_ZONE3_MUTE = "zone3Mute";
    public static final String CHANNEL_ZONE3_INPUT = "zone3Input";

    // HashMap of Zone2 Channel Type UIDs (to be added to Thing later when needed)
    public final static HashMap<ChannelTypeUID, String> ZONE2_CHANNEL_TYPES = new HashMap<ChannelTypeUID, String>();
    static {
        ZONE2_CHANNEL_TYPES.put(new ChannelTypeUID(BINDING_ID, "zone2Power"), CHANNEL_ZONE2_POWER);
        ZONE2_CHANNEL_TYPES.put(new ChannelTypeUID(BINDING_ID, "zone2Volume"), CHANNEL_ZONE2_VOLUME);
        ZONE2_CHANNEL_TYPES.put(new ChannelTypeUID(BINDING_ID, "zone2Mute"), CHANNEL_ZONE2_MUTE);
        ZONE2_CHANNEL_TYPES.put(new ChannelTypeUID(BINDING_ID, "zone2Input"), CHANNEL_ZONE2_INPUT);
    }

    // HashMap of Zone3 Channel Type UIDs (to be added to Thing later when needed)
    public final static HashMap<ChannelTypeUID, String> ZONE3_CHANNEL_TYPES = new HashMap<ChannelTypeUID, String>();
    static {
        ZONE3_CHANNEL_TYPES.put(new ChannelTypeUID(BINDING_ID, "zone3Power"), CHANNEL_ZONE3_POWER);
        ZONE3_CHANNEL_TYPES.put(new ChannelTypeUID(BINDING_ID, "zone3Volume"), CHANNEL_ZONE3_VOLUME);
        ZONE3_CHANNEL_TYPES.put(new ChannelTypeUID(BINDING_ID, "zone3Mute"), CHANNEL_ZONE3_MUTE);
        ZONE3_CHANNEL_TYPES.put(new ChannelTypeUID(BINDING_ID, "zone3Input"), CHANNEL_ZONE3_INPUT);
    }
}
