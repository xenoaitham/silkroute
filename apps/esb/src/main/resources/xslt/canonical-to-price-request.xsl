<?xml version="1.0" encoding="UTF-8"?>
<!--
  SILKROUTE Phase 2 — ESB XSLT leg (constraint C6): canonical order line ->
  frozen ERP priceForSkuRequest (urn:maple:erp:pricing:v1).

  Parameters:
    currency   CAD/SGD/CNY — derived by the ESB from the store's region (CA->CAD,
               SG->SGD, CN->CNY), because the canonical request carries no money.
    lineIndex  1-based index of the order line to price (the saga prices per line).
-->
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:can="urn:maple:canonical:order:v1"
                xmlns:mprc="urn:maple:erp:pricing:v1"
                exclude-result-prefixes="can">

  <xsl:output method="xml" encoding="UTF-8" omit-xml-declaration="yes" indent="no"/>

  <xsl:param name="currency"/>
  <xsl:param name="lineIndex" select="1"/>

  <xsl:template match="/can:order">
    <xsl:variable name="line" select="can:lines/can:line[position() = $lineIndex]"/>
    <mprc:priceForSkuRequest>
      <mprc:skuId><xsl:value-of select="$line/can:skuId"/></mprc:skuId>
      <mprc:currency><xsl:value-of select="$currency"/></mprc:currency>
    </mprc:priceForSkuRequest>
  </xsl:template>

</xsl:stylesheet>
