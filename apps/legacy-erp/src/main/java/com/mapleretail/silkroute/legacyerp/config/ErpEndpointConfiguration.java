package com.mapleretail.silkroute.legacyerp.config;

import javax.xml.namespace.QName;
import jakarta.xml.ws.Endpoint;
import jakarta.xml.ws.soap.SOAPBinding;

import org.apache.cxf.Bus;
import org.apache.cxf.binding.soap.saaj.SAAJInInterceptor;
import org.apache.cxf.jaxws.EndpointImpl;
import org.apache.cxf.transport.servlet.CXFServlet;
import org.apache.cxf.ws.security.wss4j.WSS4JInInterceptor;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mapleretail.silkroute.legacyerp.service.InventoryServiceImpl;
import com.mapleretail.silkroute.legacyerp.service.OrderServiceImpl;
import com.mapleretail.silkroute.legacyerp.service.PricingServiceImpl;

/**
 * Publishes the three ERP endpoints from their FROZEN WSDLs (constraint C6):
 * each EndpointImpl is bound to the classpath WSDL, so `GET /ws/{service}/v1?wsdl`
 * serves the frozen contract (CXF rewrites only the runtime soap:address and the
 * schema import locations). The binding is pinned to SOAP 1.2 so CXF can never
 * quietly fall back to a WSDL-ignoring SOAP 1.1 endpoint.
 *
 * <p>Endpoints are published manually (no cxf.path): one CXFServlet is registered
 * at /ws/* and the endpoints sit at relative addresses beneath it, keeping
 * explicit control of the surface. Endpoints publish before the servlet
 * initializes; CXF hands the servlet the same destination registry the
 * publications registered into.
 */
@Configuration
public class ErpEndpointConfiguration {

    public static final String SERVLET_MAPPING = "/ws/*";

    public static final String ORDERS_ADDRESS = "/orders/v1";
    public static final String INVENTORY_ADDRESS = "/inventory/v1";
    public static final String PRICING_ADDRESS = "/pricing/v1";

    public static final String ORDERS_NAMESPACE = "urn:maple:erp:orders:v1";
    public static final String INVENTORY_NAMESPACE = "urn:maple:erp:inventory:v1";
    public static final String PRICING_NAMESPACE = "urn:maple:erp:pricing:v1";

    public static final String ORDERS_WSDL_LOCATION = "wsdl/OrderService-v1.wsdl";
    public static final String INVENTORY_WSDL_LOCATION = "wsdl/InventoryService-v1.wsdl";
    public static final String PRICING_WSDL_LOCATION = "wsdl/PricingService-v1.wsdl";

    public static final String ORDERS_SERVICE_NAME = "OrderService-v1";
    public static final String INVENTORY_SERVICE_NAME = "InventoryService-v1";
    public static final String PRICING_SERVICE_NAME = "PricingService-v1";

    public static final String ORDERS_PORT_NAME = "OrderServiceSoap12Endpoint";
    public static final String INVENTORY_PORT_NAME = "InventoryServiceSoap12Endpoint";
    public static final String PRICING_PORT_NAME = "PricingServiceSoap12Endpoint";

    @Bean
    public ServletRegistrationBean<CXFServlet> legacyErpCxfServlet(Bus bus) {
        CXFServlet cxfServlet = new CXFServlet();
        cxfServlet.setBus(bus);
        return new ServletRegistrationBean<>(cxfServlet, SERVLET_MAPPING);
    }

    @Bean
    public Endpoint ordersEndpoint(Bus bus, OrderServiceImpl orderService, WSS4JInInterceptor wssInInterceptor) {
        return publish(bus, orderService, ORDERS_NAMESPACE, ORDERS_SERVICE_NAME, ORDERS_PORT_NAME,
                ORDERS_WSDL_LOCATION, ORDERS_ADDRESS, wssInInterceptor);
    }

    @Bean
    public Endpoint inventoryEndpoint(Bus bus, InventoryServiceImpl inventoryService,
                                      WSS4JInInterceptor wssInInterceptor) {
        return publish(bus, inventoryService, INVENTORY_NAMESPACE, INVENTORY_SERVICE_NAME, INVENTORY_PORT_NAME,
                INVENTORY_WSDL_LOCATION, INVENTORY_ADDRESS, wssInInterceptor);
    }

    @Bean
    public Endpoint pricingEndpoint(Bus bus, PricingServiceImpl pricingService, WSS4JInInterceptor wssInInterceptor) {
        return publish(bus, pricingService, PRICING_NAMESPACE, PRICING_SERVICE_NAME, PRICING_PORT_NAME,
                PRICING_WSDL_LOCATION, PRICING_ADDRESS, wssInInterceptor);
    }

    private static Endpoint publish(Bus bus, Object implementor, String namespace, String serviceName,
                                    String portName, String wsdlLocation, String address,
                                    WSS4JInInterceptor wssInInterceptor) {
        EndpointImpl endpoint = new EndpointImpl(bus, implementor, SOAPBinding.SOAP12HTTP_BINDING);
        endpoint.setWsdlLocation(wsdlLocation);
        endpoint.setServiceName(new QName(namespace, serviceName));
        endpoint.setEndpointName(new QName(namespace, portName));
        endpoint.getInInterceptors().add(new SAAJInInterceptor());
        endpoint.getInInterceptors().add(wssInInterceptor);
        endpoint.publish(address);
        return endpoint;
    }
}
