// Azorea - Copyright (C) 2026 General Beria
// SPDX-License-Identifier: GPL-3.0-only
// Adaptado de World Host (MIT) / UPnP Gateway (LGPL 2.1) — ver LICENSE-UPNP.txt
package com.azorea.mod.upnp;

import java.util.HashMap;
import java.util.Map;

// Descriptions and codes come from the UPnP spec at http://upnp.org/specs/gw/UPnP-gw-WANIPConnection-v2-Service.pdf
// § Nota: world-host usaba fastutil (Int2ObjectLinkedOpenHashMap). Reemplazado por HashMap
// para evitar dep externa (fastutil es transitivo de MC pero preferimos no depender de eso).
public final class UPnPErrors {
    public static final Map<Integer, AddPortMappingErrors> ADD_PORT_MAPPING_ERROR_CODES = new HashMap<>();

    static {
        for (final AddPortMappingErrors error : AddPortMappingErrors.values()) {
            ADD_PORT_MAPPING_ERROR_CODES.put(error.code, error);
        }
    }

    private UPnPErrors() {
    }

    public enum AddPortMappingErrors {
        TBD(-1),
        Action_not_authorized(606),
        WildCardNotPermittedInSrcIP(715),
        WildCardNotPermittedInExtPort(716),
        ConflictInMappingEntry(718),
        SamePortValuesRequired(724),
        OnlyPermanentLeasesSupported(725),
        RemoteHostOnlySupportsWildcard(726),
        ExternalPortOnlySupportsWildcard(727),
        NoPortMapsAvailable(728),
        ConflictWithOtherMechanisms(729),
        WildCardNotPermittedInIntPort(732)
        ;

        public final int code;

        AddPortMappingErrors(final int code) {
            this.code = code;
        }
    }

    public static AddPortMappingErrors fromCode(final int code) {
        return ADD_PORT_MAPPING_ERROR_CODES.getOrDefault(code, AddPortMappingErrors.TBD);
    }
}
