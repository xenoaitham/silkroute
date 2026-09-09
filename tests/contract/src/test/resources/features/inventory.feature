@inventory
Feature: InventoryService v1 — SOAP 1.2 contract tests (FROZEN WSDL, constraint C6)

  Black-box raw XML against urn:maple:erp:inventory:v1 at /ws/inventory/v1.
  Stock is never over-allocated: an oversell attempt faults with the typed
  OutOfStockFault whose availableQuantity matches what getStock reports, and
  the saga compensation path (release) restores availability exactly.

  Background:
    * def soap = call read('classpath:soap.js')
    * url baseUrl + '/ws/inventory/v1'

  Scenario: getStock ST-SG-01 / SKU-0007 reports the SG region and integer stock quantities
    * def security = soap.token(wsUser, wsPass)
    * def body = '<minv:getStockRequest><minv:storeId>ST-SG-01</minv:storeId><minv:skuId>SKU-0007</minv:skuId></minv:getStockRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('inventory', 'getStock')
    * method post
    * status 200
    * def stock = soap.parse(response).Envelope.Body.getStockResponse
    * match stock.storeId == 'ST-SG-01'
    * match stock.skuId == 'SKU-0007'
    # The region field is load-bearing for Phase 2 content-based routing.
    * match stock.region == 'SG'
    * match stock.onHandQuantity == '#regex [0-9]+'
    * match stock.reservedQuantity == '#regex [0-9]+'
    * match stock.availableQuantity == '#regex [0-9]+'
    # Stock invariant: onHand is fully accounted for by reserved + available.
    * def unaccounted = stock.onHandQuantity - stock.reservedQuantity - stock.availableQuantity
    * match unaccounted == 0

  Scenario: reserve then release at ST-CA-01 round-trips and restores availableQuantity exactly
    * def beforeBody = '<minv:getStockRequest><minv:storeId>ST-CA-01</minv:storeId><minv:skuId>SKU-0005</minv:skuId></minv:getStockRequest>'
    * def security = soap.token(wsUser, wsPass)
    * request soap.envelope(security, beforeBody)
    * header Content-Type = soap.contentType('inventory', 'getStock')
    * method post
    * status 200
    * def availableBefore = soap.parse(response).Envelope.Body.getStockResponse.availableQuantity
    * def uid = '' + java.lang.System.nanoTime()
    * def reserveSecurity = soap.token(wsUser, wsPass)
    * def reserveBody = '<minv:reserveRequest><minv:reservationRef>KARATE-RES-' + uid + '</minv:reservationRef><minv:storeId>ST-CA-01</minv:storeId><minv:skuId>SKU-0005</minv:skuId><minv:quantity>3</minv:quantity>' + soap.audit('minv', 'KARATE-CONTRACT', 'corr-' + uid) + '</minv:reserveRequest>'
    * request soap.envelope(reserveSecurity, reserveBody)
    * header Content-Type = soap.contentType('inventory', 'reserve')
    * method post
    * status 200
    * def reserved = soap.parse(response).Envelope.Body.reserveResponse
    * match reserved.reservationId == '#regex RES-2026-[0-9]{6}'
    * match reserved.storeId == 'ST-CA-01'
    * match reserved.skuId == 'SKU-0005'
    * match reserved.reservedQuantity == '3'
    * match reserved.remainingAvailableQuantity == '' + (availableBefore - 3)
    * def reservationId = reserved.reservationId
    # Release without quantity: the contract says omit quantity to release the full hold.
    * def releaseSecurity = soap.token(wsUser, wsPass)
    * def releaseBody = '<minv:releaseRequest><minv:reservationId>' + reservationId + '</minv:reservationId>' + soap.audit('minv', 'KARATE-CONTRACT', 'corr-' + uid) + '</minv:releaseRequest>'
    * request soap.envelope(releaseSecurity, releaseBody)
    * header Content-Type = soap.contentType('inventory', 'release')
    * method post
    * status 200
    * def released = soap.parse(response).Envelope.Body.releaseResponse
    * match released.reservationId == reservationId
    * match released.releasedQuantity == '3'
    * match released.fullyReleased == 'true'
    * match released.remainingAvailableQuantity == availableBefore
    # The hold must be gone: availability is restored to the pre-reserve value.
    * def afterSecurity = soap.token(wsUser, wsPass)
    * request soap.envelope(afterSecurity, beforeBody)
    * header Content-Type = soap.contentType('inventory', 'getStock')
    * method post
    * status 200
    * match soap.parse(response).Envelope.Body.getStockResponse.availableQuantity == availableBefore

  Scenario: reserving more than available faults OutOfStockFault and never over-allocates
    * def beforeBody = '<minv:getStockRequest><minv:storeId>ST-CN-01</minv:storeId><minv:skuId>SKU-0009</minv:skuId></minv:getStockRequest>'
    * def security = soap.token(wsUser, wsPass)
    * request soap.envelope(security, beforeBody)
    * header Content-Type = soap.contentType('inventory', 'getStock')
    * method post
    * status 200
    * def available = soap.parse(response).Envelope.Body.getStockResponse.availableQuantity
    * def oversell = (available - 0) + 1
    * def uid = '' + java.lang.System.nanoTime()
    * def reserveSecurity = soap.token(wsUser, wsPass)
    * def reserveBody = '<minv:reserveRequest><minv:reservationRef>KARATE-OVER-' + uid + '</minv:reservationRef><minv:storeId>ST-CN-01</minv:storeId><minv:skuId>SKU-0009</minv:skuId><minv:quantity>' + oversell + '</minv:quantity>' + soap.audit('minv', 'KARATE-CONTRACT', 'corr-' + uid) + '</minv:reserveRequest>'
    * request soap.envelope(reserveSecurity, reserveBody)
    * header Content-Type = soap.contentType('inventory', 'reserve')
    * method post
    * status 500
    * def raw = soap.raw(response)
    * def fault = soap.parse(response).Envelope.Body.Fault
    # The detail ELEMENT NAME is part of the frozen contract, not just the fields.
    * match raw contains '<OutOfStockFault'
    * match fault.Detail.OutOfStockFault.errorCode == 'INV-OUT-OF-STOCK'
    * match fault.Detail.OutOfStockFault.sourceSubsystem == 'INVENTORY'
    # The fault must report exactly what getStock reported — no phantom stock.
    * match fault.Detail.OutOfStockFault.availableQuantity == available
    * match fault.Detail.OutOfStockFault.requestedQuantity == '' + oversell
    * match fault.Detail.OutOfStockFault.storeId == 'ST-CN-01'
    * match fault.Detail.OutOfStockFault.skuId == 'SKU-0009'
    # And the stock row is untouched afterwards.
    * def afterSecurity = soap.token(wsUser, wsPass)
    * request soap.envelope(afterSecurity, beforeBody)
    * header Content-Type = soap.contentType('inventory', 'getStock')
    * method post
    * status 200
    * match soap.parse(response).Envelope.Body.getStockResponse.availableQuantity == available

  Scenario: releasing an unknown reservationId faults UnknownReservationFault / INV-UNKNOWN-RESERVATION
    * def security = soap.token(wsUser, wsPass)
    * def body = '<minv:releaseRequest><minv:reservationId>RES-2026-999999</minv:reservationId>' + soap.audit('minv', 'KARATE-CONTRACT', 'corr-unknown-reservation') + '</minv:releaseRequest>'
    * request soap.envelope(security, body)
    * header Content-Type = soap.contentType('inventory', 'release')
    * method post
    * status 500
    * def raw = soap.raw(response)
    * def fault = soap.parse(response).Envelope.Body.Fault
    * match raw contains '<UnknownReservationFault'
    * match fault.Detail.UnknownReservationFault.errorCode == 'INV-UNKNOWN-RESERVATION'
    * match fault.Detail.UnknownReservationFault.reservationId == 'RES-2026-999999'
