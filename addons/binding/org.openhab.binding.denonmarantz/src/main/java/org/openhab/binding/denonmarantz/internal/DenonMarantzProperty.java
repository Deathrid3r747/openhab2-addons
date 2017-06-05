/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.denonmarantz.internal;

/**
 * Subset of the usable properties.
 *
 * @author Jeroen Idserda
 */
@Deprecated
public enum DenonMarantzProperty {

    INPUT("INPUT"),
    SURROUND_MODE("SURROUNDMODE"),
    COMMAND("COMMAND"),
    MASTER_VOLUME("MV"),
    ZONE_VOLUME("ZV"),
    POWER("PW"),
    POWER_MAINZONE("ZM"),
    MUTE("MU"),
    ARTIST("ARTIST"),
    TRACK("TRACK"),
    ALBUM("ALBUM");

    private String code;

    private DenonMarantzProperty(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
