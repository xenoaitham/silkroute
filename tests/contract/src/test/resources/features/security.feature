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
