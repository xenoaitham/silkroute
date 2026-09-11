<?xml version="1.0" encoding="UTF-8"?>
<!--
  SILKROUTE Phase 2 — ESB XSLT leg (constraint C6): canonical order
  (urn:maple:canonical:order:v1) -> frozen ERP submitOrderRequest document
  (urn:maple:erp:orders:v1, audit block in urn:maple:erp:common:v1).

  XSLT 1.0 (JDK built-in Transformer engine). The ERP schema is
  elementFormDefault="qualified", so every element is namespace-qualified in its
  service/common namespace; customerRef is optional and only emitted when present.
-->
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:can="urn:maple:canonical:order:v1"
                xmlns:mord="urn:maple:erp:orders:v1"
                xmlns:mcom="urn:maple:erp:common:v1"
                exclude-result-prefixes="can">

  <xsl:output method="xml" encoding="UTF-8" omit-xml-declaration="yes" indent="no"/>

  <xsl:template match="/can:order">
    <mord:submitOrderRequest>
      <mord:externalOrderRef><xsl:value-of select="can:externalOrderRef"/></mord:externalOrderRef>
      <mord:storeId><xsl:value-of select="can:storeId"/></mord:storeId>
      <xsl:if test="can:customerRef">
        <mord:customerRef><xsl:value-of select="can:customerRef"/></mord:customerRef>
      </xsl:if>
      <mord:orderChannel><xsl:value-of select="can:channel"/></mord:orderChannel>
      <xsl:for-each select="can:lines/can:line">
        <mord:lines>
          <mord:skuId><xsl:value-of select="can:skuId"/></mord:skuId>
          <mord:quantity><xsl:value-of select="can:quantity"/></mord:quantity>
        </mord:lines>
      </xsl:for-each>
      <!-- LegacyAuditType (urn:maple:erp:common:v1): correlation ids preserved end-to-end. -->
      <mord:audit>
        <mcom:sourceSystem><xsl:value-of select="can:audit/can:sourceSystem"/></mcom:sourceSystem>
        <mcom:receivedAt><xsl:value-of select="can:audit/can:receivedAt"/></mcom:receivedAt>
        <mcom:correlationId><xsl:value-of select="can:audit/can:correlationId"/></mcom:correlationId>
      </mord:audit>
    </mord:submitOrderRequest>
  </xsl:template>

</xsl:stylesheet>
