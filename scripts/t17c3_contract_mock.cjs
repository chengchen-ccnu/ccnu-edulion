/** 只探测本机Prism四个邀请接口；请求/响应由唯一OpenAPI0.31.1校验，不向真实SMTP发信。 */
const fs = require('node:fs'); const path = require('node:path'); const assert = require('node:assert/strict');
const YAML = require('../web-apps/node_modules/yaml'); const Ajv = require('../web-apps/node_modules/ajv/dist/2020').default;
const addFormats = require('../web-apps/node_modules/ajv-formats');
const contract = YAML.parse(fs.readFileSync(path.resolve(__dirname, '../docs/api/openapi.yaml'), 'utf8'));
assert.equal(contract.info.version, '0.31.1'); const ajv = new Ajv({ strict: false }); addFormats(ajv); ajv.addSchema({ $id: 'edu', components: contract.components });
const id = '00000000-0000-4000-8000-000000000001'; let probes = 0;
/** 按契约路径/状态请求Prism；错误仍校验统一信封，不把Mock状态当真实权限或发信验证。 */
async function probe(template, method, status, body) {
  const operation = contract.paths[template][method.toLowerCase()];
  if (body) {
    const validate = ajv.compile({ $ref: 'edu' + operation.requestBody.content['application/json'].schema.$ref });
    assert.ok(validate(body), JSON.stringify(validate.errors));
  }
  const response = await fetch('http://127.0.0.1:4010' + template.replace('{invitationId}', id), {
    method, headers: { ...(template.includes('/auth/') ? {} : { Authorization: 'Bearer synthetic' }), Prefer: `code=${status}`, 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined
  });
  assert.equal(response.status, status);
  const definition = operation.responses[String(status)];
  const resolved = definition.$ref ? contract.components.responses[definition.$ref.split('/').at(-1)] : definition;
  const validate = ajv.compile({ $ref: 'edu' + resolved.content['application/json'].schema.$ref });
  assert.ok(validate(await response.json()), JSON.stringify(validate.errors)); probes++;
}
(async () => {
  const root = '/api/v1/admin/invitations';
  const paths = [
    [root, 'POST', 202, { clientRequestId: id, email: 'admin_test@example.invalid', reason: '合成测试邀请理由，已经核对' }],
    [root + '/{invitationId}', 'GET', 200],
    [root + '/{invitationId}/resend', 'POST', 202, { clientRequestId: id, expectedRevision: 0, reason: '合成测试重发理由，已经核对' }],
    ['/api/v1/auth/admin-invitations/accept', 'POST', 201, { invitationId: id, email: 'admin_test@example.invalid', secret: 's'.repeat(43), password: 'Synthetic-test-password', displayName: 'admin_test' }]
  ];
  for (const [template, method, success, body] of paths) {
    for (const status of [success, 401, 403, 404, 409, 422, 429, 503]) await probe(template, method, status, body);
  }
  console.log(`PASS OpenAPI0.31.1 Prism: four invitation routes, ${probes} validated responses; no real writes or mail`);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
