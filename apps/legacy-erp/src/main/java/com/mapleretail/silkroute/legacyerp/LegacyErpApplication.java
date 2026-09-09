package com.mapleretail.silkroute.legacyerp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SILKROUTE Phase 1 — Maple Retail (fictional) legacy ERP estate.
 *
 * <p>Simulates the untouchable on-prem ERP behind the ESB: three SOAP 1.2 services
 * (orders, inventory, pricing) published from FROZEN WSDL contracts (constraint C6),
 * secured with WS-Security UsernameToken, money as integer minor units + currency
 * code (constraint C5). All state is in-memory by design — the ERP's real database
 * stays "behind the mainframe"; this module simulates at the service layer only.
 */
@SpringBootApplication
public class LegacyErpApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegacyErpApplication.class, args);
    }
}
