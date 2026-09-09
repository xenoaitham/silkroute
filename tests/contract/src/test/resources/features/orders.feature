@orders
Feature: OrderService v1 — SOAP 1.2 contract tests (FROZEN WSDL, constraint C6)

  Black-box raw XML against urn:maple:erp:orders:v1 at /ws/orders/v1.
  Totals are priced in the STORE's region currency (CA->CAD, SG->SGD, CN->CNY)
  via the frozen CAD-base factors. Seeded base prices in CAD minor units:
  SKU-0002 = 637, SKU-0003 = 774, SKU-0007 = 1322. Business faults ride the
  SOAP 1.2 Detail element as the typed InvalidOrderFault.

  Background:
    * def soap = call read('classpath:soap.js')
    * url baseUrl + '/ws/orders/v1'
    * def uid = '' + java.lang.System.nanoTime()

  Scenario: submitOrder at ST-CA-01 prices the total in CAD and the order round-trips via getOrderStatus
    * def extRef = 'KARATE-ORD-' + uid
    * def security = soap.token(wsUser, wsPass)
    * def body = '<mord:submitOrderRequest><mord:externalOrderRef>' + extRef + '</mord:externalOrderRef><mord:storeId>ST-CA-01</mord:storeId><mord:orderChannel>WEB_STORE</mord:orderChannel><mord:lines><mord:skuId>SKU-0002</mord:skuId><mord:quantity>2</mord:quantity></mord:lines><mord:lines><mord:skuId>SKU-0003</mord:skuId><mord:quantity>1</mord:quantity></mord:lines>' + soap.audit('mord', 'KARATE-CONTRACT', 'corr-' + uid) + '</mord:submitOrderRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('orders', 'submitOrder')
    * method post
    * status 200
    * def submitted = soap.parse(response).Envelope.Body.submitOrderResponse
    * match submitted.orderId == '#regex ORD-2026-[0-9]{6}'
    * match submitted.status == 'SUBMITTED'
    * match submitted.externalOrderRef == extRef
    # (637 x 2) + (774 x 1) = 2048 CAD minor — exact integer assertion, no floats.
    * match submitted.totalAmount.amountMinor == '2048'
    * match submitted.totalAmount.currency == 'CAD'
    * match submitted.lineCount == '2'
    * def orderId = submitted.orderId
    # Fresh WSS header per request: each call gets its own nonce (replay cache is live).
    * def security2 = soap.token(wsUser, wsPass)
    * def body2 = '<mord:getOrderStatusRequest><mord:orderId>' + orderId + '</mord:orderId></mord:getOrderStatusRequest>'
    * request soap.envelope(security2, body2)
    * header Content-Type = soap.contentType('orders', 'getOrderStatus')
    * method post
    * status 200
    * def orderStatus = soap.parse(response).Envelope.Body.getOrderStatusResponse
    * match orderStatus.orderId == orderId
    * match orderStatus.externalOrderRef == extRef
    * match orderStatus.storeId == 'ST-CA-01'
    * match orderStatus.status == 'SUBMITTED'
    * match orderStatus.totalAmount.amountMinor == '2048'
    * match orderStatus.totalAmount.currency == 'CAD'
    * match orderStatus.lines[0].skuId == 'SKU-0002'
    * match orderStatus.lines[0].quantity == '2'
    * match orderStatus.lines[0].lineStatus == 'ACCEPTED'
    * match orderStatus.lines[1].skuId == 'SKU-0003'
    * match orderStatus.lines[1].quantity == '1'
    * match orderStatus.lines[1].lineStatus == 'ACCEPTED'

  Scenario: submitOrder at an SG store prices the total in SGD with the x0.98 factor
    * def extRef = 'KARATE-SG-' + uid
    * def security = soap.token(wsUser, wsPass)
    * def body = '<mord:submitOrderRequest><mord:externalOrderRef>' + extRef + '</mord:externalOrderRef><mord:storeId>ST-SG-01</mord:storeId><mord:orderChannel>POS</mord:orderChannel><mord:lines><mord:skuId>SKU-0007</mord:skuId><mord:quantity>1</mord:quantity></mord:lines>' + soap.audit('mord', 'KARATE-CONTRACT', 'corr-' + uid) + '</mord:submitOrderRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('orders', 'submitOrder')
    * method post
    * status 200
    # 1322 x 0.98 = 1295.56 -> HALF_UP -> 1296 SGD minor (frozen long-minor-unit math).
    * def submitted = soap.parse(response).Envelope.Body.submitOrderResponse
    * match submitted.totalAmount.amountMinor == '1296'
    * match submitted.totalAmount.currency == 'SGD'

  Scenario: submitOrder with a duplicate externalOrderRef for the same sourceSystem faults ORD-DUP-REF
    * def extRef = 'KARATE-DUP-' + uid
    * def submitBody = '<mord:submitOrderRequest><mord:externalOrderRef>' + extRef + '</mord:externalOrderRef><mord:storeId>ST-CA-02</mord:storeId><mord:orderChannel>PHONE</mord:orderChannel><mord:lines><mord:skuId>SKU-0003</mord:skuId><mord:quantity>1</mord:quantity></mord:lines>' + soap.audit('mord', 'KARATE-CONTRACT', 'corr-' + uid) + '</mord:submitOrderRequest>'
    * def security = soap.token(wsUser, wsPass)
    * request soap.envelope(security, submitBody)
    * header Content-Type = soap.contentType('orders', 'submitOrder')
    * method post
    * status 200
    * def security2 = soap.token(wsUser, wsPass)
    * request soap.envelope(security2, submitBody)
    * header Content-Type = soap.contentType('orders', 'submitOrder')
    * method post
    * status 500
    * def raw = soap.raw(response)
    * def fault = soap.parse(response).Envelope.Body.Fault
    # The detail ELEMENT NAME is part of the frozen contract, not just the fields.
    * match raw contains '<InvalidOrderFault'
    * match fault.Detail.InvalidOrderFault.errorCode == 'ORD-DUP-REF'
    * match fault.Detail.InvalidOrderFault.sourceSubsystem == 'ORDERS'
    * match fault.Detail.InvalidOrderFault.externalOrderRef == extRef

  Scenario: submitOrder with quantity 0 faults InvalidOrderFault / ORD-BAD-QUANTITY
    * def extRef = 'KARATE-QTY0-' + uid
    * def security = soap.token(wsUser, wsPass)
    * def body = '<mord:submitOrderRequest><mord:externalOrderRef>' + extRef + '</mord:externalOrderRef><mord:storeId>ST-CA-01</mord:storeId><mord:orderChannel>WEB_STORE</mord:orderChannel><mord:lines><mord:skuId>SKU-0001</mord:skuId><mord:quantity>0</mord:quantity></mord:lines>' + soap.audit('mord', 'KARATE-CONTRACT', 'corr-' + uid) + '</mord:submitOrderRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('orders', 'submitOrder')
    * method post
    * status 500
    * def raw = soap.raw(response)
    * match raw contains '<InvalidOrderFault'
    * match soap.parse(response).Envelope.Body.Fault.Detail.InvalidOrderFault.errorCode == 'ORD-BAD-QUANTITY'

  Scenario: submitOrder with an unknown store faults InvalidOrderFault / ORD-UNKNOWN-STORE
    * def extRef = 'KARATE-STORE-' + uid
    * def security = soap.token(wsUser, wsPass)
    * def body = '<mord:submitOrderRequest><mord:externalOrderRef>' + extRef + '</mord:externalOrderRef><mord:storeId>ST-XX-99</mord:storeId><mord:orderChannel>WEB_STORE</mord:orderChannel><mord:lines><mord:skuId>SKU-0001</mord:skuId><mord:quantity>1</mord:quantity></mord:lines>' + soap.audit('mord', 'KARATE-CONTRACT', 'corr-' + uid) + '</mord:submitOrderRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('orders', 'submitOrder')
    * method post
    * status 500
    * def raw = soap.raw(response)
    * def fault = soap.parse(response).Envelope.Body.Fault
    * match raw contains '<InvalidOrderFault'
    * match fault.Detail.InvalidOrderFault.errorCode == 'ORD-UNKNOWN-STORE'
    * match fault.Detail.InvalidOrderFault.sourceSubsystem == 'ORDERS'
    * match fault.Detail.InvalidOrderFault.invalidValue == 'ST-XX-99'

  Scenario: getOrderStatus of an unknown orderId faults InvalidOrderFault / ORD-UNKNOWN
    * def security = soap.token(wsUser, wsPass)
    * def body = '<mord:getOrderStatusRequest><mord:orderId>ORD-2026-999999</mord:orderId></mord:getOrderStatusRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('orders', 'getOrderStatus')
    * method post
    * status 500
    * def raw = soap.raw(response)
    * match raw contains '<InvalidOrderFault'
    * match soap.parse(response).Envelope.Body.Fault.Detail.InvalidOrderFault.errorCode == 'ORD-UNKNOWN'
