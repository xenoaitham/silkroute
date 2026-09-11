<?xml version="1.0" encoding="UTF-8"?>
<!--
  SILKROUTE Phase 2 — ESB XSLT leg (constraint C6): frozen ERP submitOrderResponse
  (urn:maple:erp:orders:v1) -> canonical confirmed order
  (urn:maple:canonical:order:v1).

  The frozen response does NOT echo the store or the channel, so they arrive as
  parameters supplied by the saga from the request context (storeId, channel).
  Money passes through as integer minor units + currency code (C5) — never floats.
-->
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:can="urn:maple:canonical:order:v1"
                xmlns:mord="urn:maple:erp:orders:v1"
                xmlns:mcom="urn:maple:erp:common:v1"
                exclude-result-prefixes="mord mcom">

  <xsl:output method="xml" encoding="UTF-8" omit-xml-declaration="yes" indent="no"/>

  <xsl:param name="storeId"/>
  <xsl:param name="channel"/>

  <xsl:template match="/mord:submitOrderResponse">
    <can:confirmedOrder>
      <can:orderId><xsl:value-of select="mord:orderId"/></can:orderId>
      <can:status><xsl:value-of select="mord:status"/></can:status>
      <can:externalOrderRef><xsl:value-of select="mord:externalOrderRef"/></can:externalOrderRef>
      <can:storeId><xsl:value-of select="$storeId"/></can:storeId>
      <can:channel><xsl:value-of select="$channel"/></can:channel>
      <can:submittedAt><xsl:value-of select="mord:submittedAt"/></can:submittedAt>
      <can:lineCount><xsl:value-of select="mord:lineCount"/></can:lineCount>
      <can:totalAmount>
        <can:amountMinor><xsl:value-of select="mord:totalAmount/mcom:amountMinor"/></can:amountMinor>
        <can:currency><xsl:value-of select="mord:totalAmount/mcom:currency"/></can:currency>
      </can:totalAmount>
    </can:confirmedOrder>
  </xsl:template>

</xsl:stylesheet>
