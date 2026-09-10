@security
Feature: WS-Security UsernameToken enforcement — SOAP 1.2 contract tests

  Every operation on every FROZEN WSDL requires a WS-Security UsernameToken
  (PasswordText) with a FRESH nonce. Requests without a Security header or
  with wrong credentials must fail with a generic SOAP 1.2 security fault
  (Code/Value = ...:Sender, Subcode = WSS4J's SecurityError) and never leak
  business behavior.

  Background:
    * def soap = call read('classpath:soap.js')
    * url baseUrl + '/ws/pricing/v1'

  Scenario: a request without a Security header is rejected with a SOAP security fault
    * def body = '<mprc:priceForSkuRequest><mprc:skuId>SKU-0001</mprc:skuId><mprc:currency>SGD</mprc:currency></mprc:priceForSkuRequest>'
    * request soap.envelope('', body)
    * header Content-Type = soap.contentType('pricing', 'priceForSku')
    * method post
    * status 500
    * def fault = soap.parse(response).Envelope.Body.Fault
    * match fault.Code.Value == '#regex .*:Sender'
    * match fault.Code.Subcode.Value == '#regex .*:SecurityError'
    * match fault.Reason.Text == '#string'
    # No business detail may leak through the fault.
    * match fault.Detail == '#notpresent'

  Scenario: a request with a wrong password is rejected with a SOAP security fault
    * def security = soap.token(wsUser, 'definitely-not-the-password')
    * def body = '<mprc:priceForSkuRequest><mprc:skuId>SKU-0001</mprc:skuId><mprc:currency>SGD</mprc:currency></mprc:priceForSkuRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('pricing', 'priceForSku')
    * method post
    * status 500
    * def fault = soap.parse(response).Envelope.Body.Fault
    * match fault.Code.Value == '#regex .*:Sender'
    * match fault.Code.Subcode.Value == '#regex .*:SecurityError'
    * match fault.Reason.Text == '#string'
    # No business detail may leak through the fault.
    * match fault.Detail == '#notpresent'

  Scenario: a request without a Security header is rejected on the orders endpoint too (every operation of the frozen estate enforces auth)
    * url baseUrl + '/ws/orders/v1'
    * def body = '<mord:getOrderStatusRequest><mord:orderId>ORD-2026-000001</mord:orderId></mord:getOrderStatusRequest>'
    * request soap.envelope('', body)
    * header Content-Type = soap.contentType('orders', 'getOrderStatus')
    * method post
    * status 500
    * def fault = soap.parse(response).Envelope.Body.Fault
    * match fault.Code.Value == '#regex .*:Sender'
    * match fault.Code.Subcode.Value == '#regex .*:SecurityError'
    * match fault.Reason.Text == '#string'
    # No business behavior may leak: no InvalidOrderFault detail on an unauthenticated call.
    * match fault.Detail == '#notpresent'

  Scenario: resending the identical envelope (same nonce) is rejected by the server-side replay cache
    # A token is single-use: the byte-identical envelope is sent twice. The first
    # post must succeed (proving the credentials were valid), the replay must
    # fail with a security fault — the nonce cache is live, not documentation.
    * def security = soap.token(wsUser, wsPass)
    * def replayEnvelope = soap.envelope(security, '<mprc:priceForSkuRequest><mprc:skuId>SKU-0001</mprc:skuId><mprc:currency>SGD</mprc:currency></mprc:priceForSkuRequest>')
    * header Content-Type = soap.contentType('pricing', 'priceForSku')
    * request replayEnvelope
    * method post
    * status 200
    * match soap.parse(response).Envelope.Body.priceForSkuResponse.unitPrice.amountMinor == '490'
    # Same nonce, same Created, same body — only the reused nonce can explain the rejection.
    * request replayEnvelope
    * method post
    * status 500
    * def fault = soap.parse(response).Envelope.Body.Fault
    * match fault.Code.Value == '#regex .*:Sender'
    * match fault.Code.Subcode.Value == '#regex .*:SecurityError'
    * match fault.Detail == '#notpresent'
