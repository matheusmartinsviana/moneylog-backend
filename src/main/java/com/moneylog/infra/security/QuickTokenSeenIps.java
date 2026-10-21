package com.moneylog.infra.security;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class QuickTokenSeenIps {

    private static final int MAX_TOKENS = 2_000;
    private static final int MAX_IPS_PER_TOKEN = 20;

    private final Map<UUID, Set<String>> seen = new LinkedHashMap<>();

    public synchronized boolean registerAndCheckNew(UUID tokenId, String maskedIp, String lastPersistedIp) {
        if (maskedIp == null) {
            return false;
        }
        Set<String> ips = seen.remove(tokenId);
        boolean firstEverUse = ips == null && lastPersistedIp == null;
        if (ips == null) {
            ips = new LinkedHashSet<>();
            if (lastPersistedIp != null) {
                ips.add(lastPersistedIp);
            }
        }
        boolean isNew = ips.add(maskedIp);
        if (ips.size() > MAX_IPS_PER_TOKEN) {
            Iterator<String> eldest = ips.iterator();
            eldest.next();
            eldest.remove();
        }
        seen.put(tokenId, ips);
        if (seen.size() > MAX_TOKENS) {
            Iterator<UUID> eldest = seen.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        return isNew && !firstEverUse;
    }
}
