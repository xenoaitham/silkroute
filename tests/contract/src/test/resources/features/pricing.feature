@pricing
Feature: PricingService v1 — SOAP 1.2 contract tests (FROZEN WSDL, constraint C6)

  Black-box raw XML against urn:maple:erp:pricing:v1 at /ws/pricing/v1.
  The frozen estate (seeded with Random(42), constraint C5) prices from a CAD
  base in integer minor units: SKU-0001 = 500 CAD minor, factors SGD x0.98,
  CNY x5.13, HALF_UP to a whole minor unit. Assertions are exact integer
  minor units — never floats.

  Background:
    * def soap = call read('classpath:soap.js')
    * url baseUrl + '/ws/pricing/v1'

  Scenario: priceForSku SKU-0001 in SGD returns the STD-2026 list price of 490 minor units
    * def security = soap.token(wsUser, wsPass)
    * def body = '<mprc:priceForSkuRequest><mprc:skuId>SKU-0001</mprc:skuId><mprc:currency>SGD</mprc:currency></mprc:priceForSkuRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('pricing', 'priceForSku')
    * method post
    * status 200
    * def res = soap.parse(response)
    * match res.Envelope.Body.priceForSkuResponse.skuId == 'SKU-0001'
    * match res.Envelope.Body.priceForSkuResponse.unitPrice.amountMinor == '490'
    * match res.Envelope.Body.priceForSkuResponse.unitPrice.currency == 'SGD'
    * match res.Envelope.Body.priceForSkuResponse.priceListCode == 'STD-2026'
    * match res.Envelope.Body.priceForSkuResponse.effectiveFrom == '2026-01-01'
    * match res.Envelope.Body.priceForSkuResponse.validUntil == '2026-12-31'
    * match res.Envelope.Body.priceForSkuResponse.discountEligible == 'true'

  Scenario: priceForSku SKU-0001 in CAD returns the unconverted base price of 500 minor units
    * def security = soap.token(wsUser, wsPass)
    * def body = '<mprc:priceForSkuRequest><mprc:skuId>SKU-0001</mprc:skuId><mprc:currency>CAD</mprc:currency></mprc:priceForSkuRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('pricing', 'priceForSku')
    * method post
    * status 200
    * def res = soap.parse(response)
    * match res.Envelope.Body.priceForSkuResponse.unitPrice.amountMinor == '500'
    * match res.Envelope.Body.priceForSkuResponse.unitPrice.currency == 'CAD'

  Scenario: priceForSku SKU-0001 in CNY applies the frozen x5.13 factor to 2565 minor units
    * def security = soap.token(wsUser, wsPass)
    * def body = '<mprc:priceForSkuRequest><mprc:skuId>SKU-0001</mprc:skuId><mprc:currency>CNY</mprc:currency></mprc:priceForSkuRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('pricing', 'priceForSku')
    * method post
    * status 200
    * def res = soap.parse(response)
    * match res.Envelope.Body.priceForSkuResponse.unitPrice.amountMinor == '2565'
    * match res.Envelope.Body.priceForSkuResponse.unitPrice.currency == 'CNY'

  Scenario: priceForSku unknown SKU faults with typed UnknownSkuFault / PRC-SKU-UNKNOWN
    * def security = soap.token(wsUser, wsPass)
    * def body = '<mprc:priceForSkuRequest><mprc:skuId>SKU-9999</mprc:skuId><mprc:currency>SGD</mprc:currency></mprc:priceForSkuRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('pricing', 'priceForSku')
    * method post
    * status 500
    * def raw = soap.raw(response)
    * def fault = soap.parse(response).Envelope.Body.Fault
    # The detail ELEMENT NAME is part of the frozen contract, not just the fields.
    * match raw contains '<UnknownSkuFault'
    * match fault.Detail.UnknownSkuFault.errorCode == 'PRC-SKU-UNKNOWN'
    * match fault.Detail.UnknownSkuFault.sourceSubsystem == 'PRICING'
    * match fault.Detail.UnknownSkuFault.skuId == 'SKU-9999'
