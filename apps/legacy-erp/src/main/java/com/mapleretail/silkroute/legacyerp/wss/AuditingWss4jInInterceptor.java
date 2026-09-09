package com.mapleretail.silkroute.legacyerp.wss;

import jakarta.xml.soap.SOAPHeader;
import jakarta.xml.soap.SOAPMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.ws.security.wss4j.WSS4JInInterceptor;
import org.apache.wss4j.common.WSS4JConstants;

/**
 * WSS4J UsernameToken in-interceptor that additionally logs the presented
 * username whenever token processing fails. Password material is never logged —
 * only the username and the failure reason are written.
 */
public class AuditingWss4jInInterceptor extends WSS4JInInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(AuditingWss4jInInterceptor.class);
    private static final String UNKNOWN_USERNAME = "<none-presented>";

    public AuditingWss4jInInterceptor(java.util.Map<String, Object> properties) {
        super(properties);
    }

    @Override
    public void handleMessage(SoapMessage message) {
        try {
            super.handleMessage(message);
        } catch (RuntimeException fault) {
            LOG.warn("ERP WSS: authentication rejected for username='{}' reason='{}'",
                    presentedUsername(message), fault.getMessage());
            throw fault;
        }
    }

    /**
     * Best-effort read of the presented UsernameToken username from the SAAJ
     * request header. Returns a placeholder when the header is absent or
     * unreadable (that is itself the failure reason in those cases).
     */
    private String presentedUsername(SoapMessage message) {
        SOAPMessage saaj = message.getContent(SOAPMessage.class);
        if (saaj == null) {
            return UNKNOWN_USERNAME;
        }
        try {
            SOAPHeader header = saaj.getSOAPHeader();
            if (header == null) {
                return UNKNOWN_USERNAME;
            }
            NodeList securityHeaders = header.getElementsByTagNameNS(WSS4JConstants.WSSE_NS, "Security");
            for (int i = 0; i < securityHeaders.getLength(); i++) {
                NodeList children = securityHeaders.item(i).getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if (child instanceof Element token
                            && WSS4JConstants.USERNAME_TOKEN_LN.equals(token.getLocalName())
                            && WSS4JConstants.WSSE_NS.equals(token.getNamespaceURI())) {
                        NodeList usernames = token.getElementsByTagNameNS(WSS4JConstants.WSSE_NS,
                                WSS4JConstants.USERNAME_LN);
                        if (usernames.getLength() > 0) {
                            String username = usernames.item(0).getTextContent();
                            return username == null || username.isBlank() ? UNKNOWN_USERNAME : username;
                        }
                    }
                }
            }
            return UNKNOWN_USERNAME;
        } catch (Exception e) {
            // SAAJ access can throw SOAPException; a username we cannot read is
            // itself the audit-relevant fact.
            return UNKNOWN_USERNAME;
        }
    }
}
