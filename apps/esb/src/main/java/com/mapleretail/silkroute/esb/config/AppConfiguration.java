package com.mapleretail.silkroute.esb.config;

import com.mapleretail.silkroute.esb.canonical.CanonicalXmlCodec;
import com.mapleretail.silkroute.esb.erp.ErpXmlCodec;
import com.mapleretail.silkroute.esb.erp.FaultInjectionFeature;
import com.mapleretail.silkroute.esb.xslt.XsltTransformer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Stateless infrastructure beans (codecs, XSLT engine, fault-injection hook). */
@Configuration
public class AppConfiguration {

    @Bean
    public XsltTransformer xsltTransformer() {
        return new XsltTransformer();
    }

    @Bean
    public CanonicalXmlCodec canonicalXmlCodec() {
        return new CanonicalXmlCodec();
    }

    @Bean
    public ErpXmlCodec erpXmlCodec() {
        return new ErpXmlCodec();
    }

    /** ESB_FAULT_INJECTION=false by default: the X-Fault-Injection header is ignored. */
    @Bean
    public FaultInjectionFeature faultInjectionFeature(EsbProperties properties) {
        return new FaultInjectionFeature(properties.isFaultInjection());
    }
}
