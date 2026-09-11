package com.mapleretail.silkroute.esb.config;

import java.util.HashMap;
import java.util.Map;

import com.mapleretail.silkroute.esb.erp.ErpPorts;
import com.mapleretail.silkroute.esb.erp.WssPasswordCallbackHandler;

import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor;
import org.apache.wss4j.common.WSS4JConstants;
import org.apache.wss4j.dom.handler.WSHandlerConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import maple.erp.inventory.v1.InventoryServicePortType;
import maple.erp.orders.v1.OrderServicePortType;
import maple.erp.pricing.v1.PricingServicePortType;

/**
 * CXF SOAP client wiring for the FROZEN ERP estate. Wire-exact WS-Security
 * (hard-won in Phase 2 S2 — the ERP has a BSP-strict, nonce-replay-caching
 * WSS4JInInterceptor):
 *
 *  - UsernameToken, PasswordText. WSS4JOutInterceptor with passwordType
 *    PasswordText emits the OASIS UsernameToken PROFILE URI on the wire
 *    (...username-token-profile-1.0#PasswordText) — the secext spelling is
 *    rejected.
 *  - addNonce=true: a FRESH SecureRandom Base64 nonce per request WITH
 *    EncodingType=...soap-message-security-1.0#Base64Binary (BSP:R4220) — the
 *    ERP keeps a live replay cache.
 *  - addCreated=true: wsu:Created UTC xsd:dateTime.
 *
 * If a wire call is ever rejected on security grounds, the fallback is to build
 * the Security header DOM exactly like scripts/wss-header.sh (see README).
 */
@Configuration
public class ErpClientConfiguration {

    /** ESB→ERP calls go THROUGH toxiproxy (fault injection point) by default. */
    public static final String ORDERS_PATH = "/ws/orders/v1";
    public static final String INVENTORY_PATH = "/ws/inventory/v1";
    public static final String PRICING_PATH = "/ws/pricing/v1";

    private final EsbProperties properties;

    public ErpClientConfiguration(EsbProperties properties) {
        this.properties = properties;
    }

    @Bean
    public WSS4JOutInterceptor erpWssOutInterceptor() {
        EsbProperties.Erp.Wss wss = properties.getErp().getWss();
        Map<String, Object> outProps = new HashMap<>();
        outProps.put(WSHandlerConstants.ACTION, WSHandlerConstants.USERNAME_TOKEN);
        // "PasswordText" selector -> WSS4J emits the OASIS profile URI Type on the wire.
        outProps.put(WSHandlerConstants.PASSWORD_TYPE, WSS4JConstants.PW_TEXT);
        outProps.put(WSHandlerConstants.USER, wss.getUsername());
        outProps.put(WSHandlerConstants.PW_CALLBACK_REF, new WssPasswordCallbackHandler(wss.getPassword()));
        // Fresh nonce per request with Base64Binary EncodingType (BSP:R4220) + wsu:Created (UTC).
        outProps.put("addNonce", "true");
        outProps.put("addCreated", "true");
        return new WSS4JOutInterceptor(outProps);
    }

    @Bean
    public ErpPorts erpPorts(WSS4JOutInterceptor erpWssOutInterceptor) {
        String base = properties.getErp().getBaseUrl();
        OrderServicePortType orders = port(OrderServicePortType.class, base + ORDERS_PATH, erpWssOutInterceptor);
        InventoryServicePortType inventory = port(InventoryServicePortType.class, base + INVENTORY_PATH,
                erpWssOutInterceptor);
        PricingServicePortType pricing = port(PricingServicePortType.class, base + PRICING_PATH,
                erpWssOutInterceptor);
        return new ErpPorts(orders, inventory, pricing);
    }

    private <T> T port(Class<T> serviceClass, String address, WSS4JOutInterceptor wss) {
        JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
        factory.setServiceClass(serviceClass);
        factory.setAddress(address);
        // The frozen estate serves SOAP 1.2 (soap12 bindings in the WSDLs).
        factory.setBindingId("http://www.w3.org/2003/05/soap/bindings/HTTP/");
        factory.getOutInterceptors().add(wss);
        T port = serviceClass.cast(factory.create());

        // Per-call ERP timeouts (documented choice: fast-fail inside the C3 budget).
        Client client = ClientProxy.getClient(port);
        HTTPConduit conduit = (HTTPConduit) client.getConduit();
        HTTPClientPolicy policy = new HTTPClientPolicy();
        policy.setConnectionTimeout(properties.getErp().getConnectTimeoutMs());
        policy.setReceiveTimeout(properties.getErp().getReceiveTimeoutMs());
        conduit.setClient(policy);
        return port;
    }
}
