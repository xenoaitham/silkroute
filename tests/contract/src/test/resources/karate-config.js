/*
 * Karate configuration for the legacy ERP SOAP contract suite.
 *
 * baseUrl resolution order:
 *   1. system property erp.baseurl    (set by LegacyErpTestApp, wins if present)
 *   2. env ERP_TEST_BASEURL           (point the suite at an already-running app)
 *   3. env ERP_TEST_PORT / sysprop erp.test.port, else the default sim port 18090
 *
 * The WSS credentials mirror the app's ${VAR:-default} indirection in
 * apps/legacy-erp/src/main/resources/application.yml — both sides fall back to
 * the same sim dummies, so the suite and the app agree without configuration.
 */
function fn() {
  var env = karate.env || 'contract';

  var sysBaseUrl = karate.properties['erp.baseurl'];
  var baseUrl = sysBaseUrl || java.lang.System.getenv('ERP_TEST_BASEURL');
  if (!baseUrl) {
    var port = karate.properties['erp.test.port'] || java.lang.System.getenv('ERP_TEST_PORT') || '18090';
    baseUrl = 'http://127.0.0.1:' + port;
  }

  var wsUser = java.lang.System.getenv('ERP_WSS_USERNAME') || 'esb-client';
  var wsPass = java.lang.System.getenv('ERP_WSS_PASSWORD') || 'erp-wss-pass-2026';

  karate.configure('connectTimeout', 15000);
  karate.configure('readTimeout', 30000);

  karate.log('contract suite config: baseUrl={}, wsUser={}', baseUrl, wsUser);
  return { env: env, baseUrl: baseUrl, wsUser: wsUser, wsPass: wsPass };
}
