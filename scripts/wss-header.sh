#!/usr/bin/env bash
# ORCH-LEAD verification helper — emits a WS-Security UsernameToken header (PasswordText).
# usage: wss.sh <username> <password>
user="$1"; pass="$2"
created="$(date -u +%Y-%m-%dT%H:%M:%S.000Z)"
nonce="$(openssl rand -hex 16)"
# Password Type = OASIS UsernameToken Profile 1.1 URI (NOT the secext element
# namespace — WSS4J enforces the profile URI on the wire and rejects the secext
# spelling as an unknown custom type). Nonce needs an EncodingType (BSP:R4220).
printf '<wsse:Security xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" soap:mustUnderstand="1"><wsse:UsernameToken><wsse:Username>%s</wsse:Username><wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText">%s</wsse:Password><wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">%s</wsse:Nonce><wsu:Created xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">%s</wsu:Created></wsse:UsernameToken></wsse:Security>' "$user" "$pass" "$nonce" "$created"
