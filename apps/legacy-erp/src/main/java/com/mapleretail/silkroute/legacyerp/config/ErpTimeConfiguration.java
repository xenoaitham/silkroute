package com.mapleretail.silkroute.legacyerp.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single clock the estate reads; injectable so the hold-expiry behavior is
 * deterministically testable.
 */
@Configuration
public class ErpTimeConfiguration {

    @Bean
    public Clock erpClock() {
        return Clock.systemUTC();
    }
}
