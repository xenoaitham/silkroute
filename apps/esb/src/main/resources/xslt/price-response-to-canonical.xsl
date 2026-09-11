<?xml version="1.0" encoding="UTF-8"?>
<!--
  SILKROUTE Phase 2 — ESB XSLT leg (constraint C6): frozen ERP priceForSkuResponse
  (urn:maple:erp:pricing:v1) -> canonical unit price
  (urn:maple:canonical:order:v1). Money stays integer minor units + currency (C5).
-->
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:can="urn:maple:canonical:order:v1"
                xmlns:mprc="urn:maple:erp:pricing:v1"
                xmlns:mcom="urn:maple:erp:common:v1"
                exclude-result-prefixes="mprc mcom">

  <xsl:output method="xml" encoding="UTF-8" omit-xml-declaration="yes" indent="no"/>

  <xsl:template match="/mprc:priceForSkuResponse">
    <can:unitPrice>
      <can:skuId><xsl:value-of select="mprc:skuId"/></can:skuId>
      <can:amountMinor><xsl:value-of select="mprc:unitPrice/mcom:amountMinor"/></can:amountMinor>
      <can:currency><xsl:value-of select="mprc:unitPrice/mcom:currency"/></can:currency>
    </can:unitPrice>
  </xsl:template>

</xsl:stylesheet>
