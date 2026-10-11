package com.moneylog.core.sk.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public interface TimeProvider {

    Instant now();

    LocalDate today();

    ZoneId zone();
}
