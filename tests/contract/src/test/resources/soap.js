/*
 * SILKROUTE contract tests — wire-exact SOAP 1.2 + WS-Security UsernameToken helper.
 *
 * Every request needs a FRESH token: the ERP keeps a nonce replay cache, so a
 * reused security header is correctly rejected. Call token() once per request.
 *
 * Wire-exact requirements enforced here (verified against the BSP-strict server):
 *  - Password Type MUST be the OASIS UsernameToken PROFILE URI
 *    ".../oasis-200401-wss-username-token-profile-1.0#PasswordText"
 *    (NOT the wsse secext element namespace — the server rejects that spelling).
 *  - Nonce MUST carry EncodingType
 *    ".../oasis-200401-wss-soap-message-security-1.0#Base64Binary" (BSP:R4220)
 *    and hold freshly generated SecureRandom bytes, Base64-encoded.
 *  - wsu:Created with a UTC xsd:dateTime.
 *
 * parse(): the server (CXF) chooses its own namespace prefixes per response —
 * the envelope is soap:*, business elements ride a default namespace, and common
 * types come out as ns2:*. Karate's XML representation keys elements by their
 * LITERAL prefix-qualified name, so assertions like
 * response.Envelope.Body...unitPrice.amountMinor would be coupled to the
 * server's throwaway prefix choices (soap:, ns2:) — which are NOT part of the
 * frozen contract (C6). parse() therefore re-parses the raw XML with a
 * namespace-aware DOM and exposes elements by LOCAL NAME:
 *   res.Envelope.Body.Fault.Detail.UnknownSkuFault.errorCode
 * Repeated elements (e.g. order lines) become arrays in document order; simple
 * text elements become strings (money stays an exact integer-minor-unit string).
 */
function fn() {
  var SecureRandom = Java.type('java.security.SecureRandom');
  var Base64 = Java.type('java.util.Base64');
  var ByteArray = Java.type('byte[]');
  var Instant = Java.type('java.time.Instant');
  var ZoneOffset = Java.type('java.time.ZoneOffset');
  var DateTimeFormatter = Java.type('java.time.format.DateTimeFormatter');
  var DocumentBuilderFactory = Java.type('javax.xml.parsers.DocumentBuilderFactory');
  var StringReader = Java.type('java.io.StringReader');
  var InputSource = Java.type('org.xml.sax.InputSource');

  var random = new SecureRandom();
  var UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  function xmlEscape(value) {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function nowUtc() {
    return UTC.format(Instant.now());
  }

  // Fresh UsernameToken (PasswordText) — call once per request, never reuse.
  function token(user, pass) {
    var bytes = new ByteArray(16);
    random.nextBytes(bytes);
    var nonce = Base64.getEncoder().encodeToString(bytes);
    return '<wsse:Security xmlns:soap="http://www.w3.org/2003/05/soap-envelope"'
      + ' xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"'
      + ' soap:mustUnderstand="1">'
      + '<wsse:UsernameToken>'
      + '<wsse:Username>' + xmlEscape(user) + '</wsse:Username>'
      + '<wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText">'
      + xmlEscape(pass) + '</wsse:Password>'
      + '<wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">'
      + nonce + '</wsse:Nonce>'
      + '<wsu:Created xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">'
      + nowUtc() + '</wsu:Created>'
      + '</wsse:UsernameToken></wsse:Security>';
  }

  // SOAP 1.2 action travels in the Content-Type parameter (document/literal wrapped).
  function contentType(domain, operation) {
    return 'application/soap+xml; charset=utf-8; action="urn:maple:erp:' + domain + ':v1:' + operation + '"';
  }

  function envelope(securityXml, bodyXml) {
    return '<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"'
      + ' xmlns:mord="urn:maple:erp:orders:v1"'
      + ' xmlns:minv="urn:maple:erp:inventory:v1"'
      + ' xmlns:mprc="urn:maple:erp:pricing:v1"'
      + ' xmlns:mc="urn:maple:erp:common:v1">'
      + '<soap:Header>' + securityXml + '</soap:Header>'
      + '<soap:Body>' + bodyXml + '</soap:Body></soap:Envelope>';
  }

  // LegacyAuditType (urn:maple:erp:common:v1). prefix is 'mord' or 'minv' — the
  // audit ELEMENT lives in the service namespace, its type in the common namespace.
  function audit(prefix, sourceSystem, correlationId) {
    return '<' + prefix + ':audit>'
      + '<mc:sourceSystem>' + xmlEscape(sourceSystem) + '</mc:sourceSystem>'
      + '<mc:receivedAt>' + nowUtc() + '</mc:receivedAt>'
      + '<mc:correlationId>' + xmlEscape(correlationId) + '</mc:correlationId>'
      + '</' + prefix + ':audit>';
  }

  // -- namespace-agnostic DOM -> plain-JS-object conversion -------------------

  // ELEMENT_NODE = 1, TEXT = 3, CDATA = 4.
  function nodeIsElement(node) {
    return node.getNodeType() === 1;
  }

  function toJs(element) {
    var out = {};
    var text = '';
    var children = element.getChildNodes();
    for (var i = 0; i < children.getLength(); i++) {
      var node = children.item(i);
      var type = node.getNodeType();
      if (type === 1) {
        // Local name only: namespace prefixes are a wire serialization detail.
        var name = node.getLocalName() || node.getNodeName();
        var value = toJs(node);
        var existing = out[name];
        if (existing === undefined) {
          out[name] = value;
        } else if (Array.isArray(existing)) {
          existing.push(value);
        } else {
          out[name] = [existing, value];
        }
      } else if (type === 3 || type === 4) {
        text += node.getNodeValue();
      }
    }
    // Element with children -> object; element with only text -> the trimmed text.
    return Object.keys(out).length === 0 ? text.trim() : out;
  }

  // Raw XML as a string — Karate auto-parses responses of unknown content types
  // (application/soap+xml) into its prefix-qualified XML map, and karate.toString()
  // on that map returns JSON, not XML. prettyXml() round-trips the actual markup,
  // which is what element-name substring assertions need.
  function raw(response) {
    return (typeof response === 'string') ? response : karate.prettyXml(response);
  }

  // Accepts the raw XML string (or the Karate response variable) and returns the
  // local-name tree — see the header comment for the shape.
  function parse(response) {
    var xml = raw(response);
    var dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    try {
      // Parse nothing but the message itself — no DTDs, no external entities.
      dbf.setFeature('http://apache.org/xml/features/disallow-doctype-decl', true);
    } catch (e) {
      // Parser variance is not a contract concern; keep going with defaults.
    }
    var document = dbf.newDocumentBuilder()
      .parse(new InputSource(new StringReader(xml)));
    var root = document.getDocumentElement();
    // Key the tree by the root's local name so paths read res.Envelope.Body...
    var out = {};
    out[root.getLocalName() || root.getNodeName()] = toJs(root);
    return out;
  }

  return {
    token: token,
    contentType: contentType,
    envelope: envelope,
    audit: audit,
    nowUtc: nowUtc,
    raw: raw,
    parse: parse
  };
}
