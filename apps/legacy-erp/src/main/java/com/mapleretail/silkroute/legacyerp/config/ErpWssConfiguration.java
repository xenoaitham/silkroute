package com.mapleretail.silkroute.legacyerp.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.cxf.ws.security.wss4j.WSS4JInInterceptor;
import org.apache.wss4j.common.WSS4JConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mapleretail.silkroute.legacyerp.wss.AuditingWss4jInInterceptor;
import com.mapleretail.silkroute.legacyerp.wss.ErpPasswordCallbackHandler;
import com.mapleretail.silkroute.legacyerp.wss.ErpWssCredentials;
import com.mapleretail.silkroute.legacyerp.wss.ErpWssPropertyKeys;

/**
 * WS-Security UsernameToken validation shared by every ERP endpoint
 * (PasswordText is acceptable for the localhost sim). Missing or invalid tokens
 * surface as SOAP security faults from WSS4J; the callback resolves the expected
 * password from environment-indirected sim credentials.
 *
 * passwordType is set to the short selector (PW_TEXT = "PasswordText"): WSS4J's
 * WSHandler.decodePasswordType maps that to the required wire type
 * "...username-token-profile-1.0#PasswordText", which is exactly the Type URI the
 * OASIS UsernameToken profile mandates on the wire.
 */
@Configuration
public class ErpWssConfiguration {

    @Bean
    public WSS4JInInterceptor erpWssInInterceptor(ErpWssCredentials credentials) {
        Map<String, Object> inProperties = new HashMap<>();
        inProperties.put(ErpWssPropertyKeys.ACTION, ErpWssPropertyKeys.USERNAME_TOKEN_ACTION);
        inProperties.put(ErpWssPropertyKeys.PASSWORD_TYPE, WSS4JConstants.PW_TEXT);
        inProperties.put(ErpWssPropertyKeys.PASSWORD_CALLBACK_REF, new ErpPasswordCallbackHandler(credentials));
        return new AuditingWss4jInInterceptor(inProperties);
    }
}
