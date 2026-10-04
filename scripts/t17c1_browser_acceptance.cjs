/** T17C.1可重复浏览器验收：真实Gateway/IAM/Media/OSS/RabbitMQ闭环与显式Mock故障分开记录。
 * 只用DPAPI包装器注入的合成密码，Token仅在内存；不保存会话、短期URL或正文日志。
 */
const { chromium } = require('playwright');
const fs = require('node:fs'); const path = require('node:path'); const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process'); const crypto = require('node:crypto');
const directory = path.resolve(__dirname, '../.m2/t17c1-browser');
// Mock响应使用同一OpenAPI校验，没有维护第二套Mock Schema。
const YAML = require('../web-apps/node_modules/yaml');
const Ajv = require('../web-apps/node_modules/ajv/dist/2020').default;
const addFormats = require('../web-apps/node_modules/ajv-formats');
const contract = YAML.parse(fs.readFileSync(path.resolve(__dirname, '../docs/api/openapi.yaml'), 'utf8'));
assert.equal(contract.info.version, '0.31.0');
const ajv = new Ajv({ strict: false }); addFormats(ajv);
ajv.addSchema({ $id: 'edu', components: contract.components });
const validateError = ajv.compile({ $ref: 'edu#/components/schemas/ApiError' });
const validateDetail = ajv.compile({ $ref: 'edu#/components/schemas/AdminTeacherApplicationDetail' });
function faultBody() {
  const body = { code: 'TEST.FAULT', message: '模拟依赖失败', traceId: 'test', timestamp: new Date().toISOString(), path: '/test', fieldErrors: [] };
  assert.ok(validateError(body)); return JSON.stringify(body);
}
const data = JSON.parse(fs.readFileSync(path.join(directory, 'fixture.json'), 'utf8'));
const tokens = {}; const results = process.env.T17C1_BROWSER_MODE === 'download'
  ? JSON.parse(fs.readFileSync(path.join(directory, 'browser-results.json'), 'utf8')) : [];
const adminUrl = 'http://localhost:5173/admin/teacher-review';
function report(kind, label) { results.push({ kind, label }); console.log(`PASS [${kind}] ${label}`); }
/** 使用统一登录表单进入指定白名单页面，捕获的Access只用于内存事实校验。 */
async function login(context, name, target) {
  const page = await context.newPage();
  page.on('requestfailed', request => console.log('Browser request failure:', JSON.stringify({ method: request.method(), gateway: new URL(request.url()).port === '8080', error: request.failure()?.errorText })));
  page.on('console', message => { if (message.type() === 'error' && /CORS/.test(message.text())) console.log('Browser CORS rejection observed (URL/headers redacted)'); });
  page.on('response', async response => {
    if (response.url().endsWith('/api/v1/auth/login') && response.status() === 200) tokens[name] = (await response.json()).accessToken;
  });
  const url = new URL(target);
  await page.goto(`http://localhost:5174/login?redirect=${encodeURIComponent(url.pathname + url.search)}`);
  await page.getByPlaceholder('请输入已验证邮箱或手机号').fill(`t17c1-${data[name]}@example.invalid`);
  await page.getByPlaceholder('请输入密码').fill(process.env.T17C1_FIXTURE_PASSWORD);
  // 多错误上下文的真实登录也服从Gateway限流；只按Retry-After有限等待，不删除共享限流Key。
  for (let attempt = 0; attempt < 3; attempt++) {
    const response = page.waitForResponse(value => value.url().endsWith('/api/v1/auth/login') && value.request().method() === 'POST');
    await page.getByRole('button', { name: '登录平台' }).click(); const loginResponse = await response;
    if (loginResponse.status() === 200) { tokens[name] = (await loginResponse.json()).accessToken; break; }
    if (loginResponse.status() !== 429 || attempt === 2) throw new Error(`Fixture login failed with ${loginResponse.status()}`);
    const seconds = Math.max(1, Number(loginResponse.headers()['retry-after']) || 30);
    console.log(`Fixture login respects Retry-After: ${seconds}s`);
    await new Promise(resolve => setTimeout(resolve, seconds * 1000 + 1000));
  }
  await page.waitForURL(target, { timeout: 30000 });
  // 等目标应用完成Cookie恢复和第一屏查询，避免立即整页跳转打断在途Refresh Cookie轮换。
  if (url.pathname.startsWith('/admin/')) await page.locator('.review-rows li').first().waitFor();
  return page;
}
async function waitFor(check, timeout = 20000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await check()) return; await new Promise(resolve => setTimeout(resolve, 200)); }
  throw new Error('Bounded acceptance condition timeout');
}
async function api(context, name, method, endpoint, body, expected = 200) {
  const response = await context.request.fetch(`http://localhost:8080/api/v1${endpoint}`, {
    method, headers: { Authorization: `Bearer ${tokens[name]}` }, data: body
  });
  assert.equal(response.status(), expected, `${method} ${endpoint} expected ${expected}, got ${response.status()}`);
  return response.status() === 204 ? null : response.json();
}
/** 实际学生上传器完成OSS直传和READY轮询，申请提交响应保存UUID，不能直接插入申请或伪造READY。 */
async function submit(page, resubmit = false) {
  await page.locator('input[type=file]').setInputFiles(path.join(directory, 'qualification.png'));
  await page.getByPlaceholder('请说明你的教学经历和申请理由（20～2000 字）').fill('student_test_t17c1 合成资质，仅用于真实审核前端验收，非真实个人资料。');
  const button = page.getByRole('button', { name: resubmit ? '重新提交申请' : '提交申请', exact: true });
  await waitFor(async () => {
    const error = page.locator('.application-form .panel-error');
    if (await error.count() && await error.first().isVisible()) throw new Error('Student qualification upload failed: ' + await error.first().innerText());
    return !await button.isDisabled();
  }, 60000);
  const pending = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/api/v1/teacher-applications'));
  await button.click(); const response = await pending; assert.ok([200, 201].includes(response.status())); return response.json();
}
async function detailReady(page) { await page.getByText('首次查看者', { exact: true }).waitFor(); }
async function reject(page, reason) {
  page.once('dialog', dialog => dialog.accept()); await page.getByLabel('驳回理由（10～1000字）').fill(reason);
  const pending = page.waitForResponse(response => response.url().endsWith('/decision') && response.request().method() === 'POST');
  await page.getByRole('button', { name: '驳回申请', exact: true }).click(); return pending;
}

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' }); const contexts = [];
  try {
    const create = async () => { const context = await browser.newContext({ viewport: { width: 1440, height: 960 } }); contexts.push(context); return context; };
    // 已完成闭环后可独立验证保留材料下载，不创建更多申请或重放决定。
    if (process.env.T17C1_BROWSER_MODE === 'download') {
      const page = await login(await create(), 'admin', `${adminUrl}?applicationId=${data.application}`); await detailReady(page);
      const pending = page.waitForEvent('download'); await page.getByRole('button', { name: '下载第 1 轮材料', exact: true }).click();
      const downloaded = await pending; assert.equal(await downloaded.failure(), null);
      report('real', 'retained current material authorized DOWNLOAD and successful browser Blob download'); return;
    }
    const student = await create(); const resubmitter = await create(); const admin = await create(); const competitor = await create();
    const learner = await login(student, 'student', 'http://localhost:5174/apply-teacher');
    const first = await submit(learner); data.application = first.id;
    const secondLearner = await login(resubmitter, 'resubmit', 'http://localhost:5174/apply-teacher');
    const second = await submit(secondLearner); data.secondApplication = second.id;
    fs.writeFileSync(path.join(directory, 'fixture.json'), JSON.stringify(data));
    report('real', 'student UI upload READY and submit two qualification applications');
    const manager = await login(admin, 'admin', adminUrl);
    await waitFor(async () => await manager.getByRole('link', { name: '教师审核', exact: true }).getAttribute('aria-current') === 'page');
    assert.equal(await manager.getByRole('link', { name: /账号权限/ }).count(), 0);
    await manager.locator('.review-rows li').filter({ hasText: first.id }).getByRole('button').click(); await detailReady(manager);
    assert.equal(new URL(manager.url()).searchParams.get('applicationId'), first.id);
    assert.equal((await api(admin, 'admin', 'GET', '/admin/teacher-applications')).items.find(row => row.id === second.id).status, 'SUBMITTED');
    report('real', 'fixed shell and list does not start other reviews, explicit detail first viewer');
    await manager.getByRole('button', { name: '预览第 1 轮材料', exact: true }).click();
    await waitFor(async () => manager.locator('.material-preview img').evaluateAll(images => images.some(img => img.complete && img.naturalWidth > 0)));
    const downloading = manager.waitForEvent('download'); await manager.getByRole('button', { name: '下载第 1 轮材料', exact: true }).click();
    assert.equal(await (await downloading).failure(), null);
    await manager.getByText('材料已读取并交给浏览器下载，请人工核对原件。', { exact: true }).waitFor();
    await manager.getByLabel('已人工核对当前轮资质材料').check();
    const saved = manager.waitForResponse(response => response.url().endsWith('/decision'));
    manager.once('dialog', dialog => dialog.accept()); await manager.getByRole('button', { name: '批准申请', exact: true }).click();
    const approvedResponse = await saved; assert.equal(approvedResponse.status(), 200); const approved = await approvedResponse.json();
    const approvalBody = approvedResponse.request().postDataJSON();
    const replayed = await api(admin, 'admin', 'POST', `/admin/teacher-applications/${first.id}/decision`, approvalBody);
    assert.equal(replayed.revision, approved.revision); assert.equal(replayed.rounds.length, 1);
    await learner.getByRole('button', { name: '刷新申请状态', exact: true }).click();
    await learner.getByRole('link', { name: '进入教学空间 →', exact: true }).waitFor();
    const refreshed = await api(student, 'student', 'POST', '/auth/refresh', undefined);
    tokens.student = refreshed.accessToken; const user = await api(student, 'student', 'GET', '/users/me');
    assert.ok(user.permissions.includes('learning:access') && user.permissions.includes('teaching:access'));
    report('real', 'OSS material preview, explicit approval, stable request replay and same student capability refresh');
    await waitFor(async () => (await api(student, 'student', 'GET', '/notifications')).items.some(item => item.targetId === first.id));
    report('real', 'review result notification delivered asynchronously through RabbitMQ');
    for (const legacy of ['/admin/dashboard', '/admin/reviews', '/']) {
      await manager.goto('http://localhost:5173' + legacy); await manager.waitForURL(adminUrl);
    }
    await manager.goto(`${adminUrl}?applicationId=${second.id}`); await detailReady(manager);
    assert.equal((await reject(manager, 'resubmit_test_t17c1：请重新上传有效资质材料。')).status(), 200);
    await secondLearner.getByRole('button', { name: '刷新申请状态', exact: true }).click();
    await secondLearner.getByRole('button', { name: '重新提交申请', exact: true }).waitFor();
    const resubmitted = await submit(secondLearner, true); assert.equal(resubmitted.id, second.id);
    await manager.reload(); await detailReady(manager); await manager.getByRole('button', { name: '只读历史', exact: true }).click();
    await manager.getByRole('heading', { name: '第 1 轮 · 已驳回', exact: true }).waitFor();
    assert.equal(await manager.getByRole('button', { name: '批准申请', exact: true }).count(), 0);
    await manager.getByRole('button', { name: '当前轮次', exact: true }).click();
    report('real', 'legacy redirects, rejection and UI resubmission with immutable round history');
    const rival = await login(competitor, 'competitor', `${adminUrl}?applicationId=${second.id}`); await detailReady(rival);
    const latest = await api(admin, 'admin', 'GET', `/admin/teacher-applications/${second.id}`);
    const removed = spawnSync('pwsh', ['-NoProfile', '-File', path.join(__dirname, 'Invoke-T17C1Fixture.ps1'), '-Action', 'remove-material'], { encoding: 'utf8' });
    assert.equal(removed.status, 0, 'exact temporary material removal failed');
    await manager.getByRole('button', { name: '预览第 2 轮材料', exact: true }).click();
    await manager.getByText(/材料无法读取|材料依赖或服务暂不可用/).waitFor();
    assert.equal(await manager.getByRole('button', { name: '批准申请', exact: true }).isDisabled(), true);
    await api(admin, 'admin', 'POST', `/admin/teacher-applications/${second.id}/decision`, {
      clientRequestId: crypto.randomUUID(), expectedRevision: latest.revision, decision: 'APPROVED', materialConfirmed: true
    }, 422);
    report('real fault', 'exact OSS object unavailable: UI and server both reject approval');
    assert.equal((await reject(rival, 'competitor_test_t17c1：材料不可读取，请重新提交。')).status(), 200);
    assert.equal((await reject(manager, 'admin_test_t17c1：并发旧版本不能覆盖已保存决定。')).status(), 409);
    await manager.getByText('存在并发冲突，不能覆盖旧决定。').waitFor();
    await manager.getByRole('button', { name: '刷新详情并核对', exact: true }).click();
    await manager.getByRole('heading', { name: /resubmit_test_t17c1 · 已驳回/ }).waitFor();
    assert.equal(await manager.getByRole('button', { name: '驳回申请', exact: true }).count(), 0);
    report('real', 'two administrators race: stale revision 409 with explicit refresh, no automatic overwrite');
    await api(resubmitter, 'resubmit', 'GET', '/admin/teacher-applications', undefined, 403);
    await secondLearner.goto(adminUrl); await secondLearner.waitForURL(/\/forbidden$/);
    report('real', 'non-administrator rejected by Gateway and frontend direct deep link');
    await manager.goto(`${adminUrl}?applicationId=${first.id}&panel=history`); await detailReady(manager);
    await manager.reload(); await detailReady(manager);
    for (const width of [360, 768, 1440]) {
      await manager.setViewportSize({ width, height: 960 });
      assert.ok(await manager.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `horizontal overflow ${width}`);
      await manager.screenshot({ path: path.join(directory, `admin-${width}.png`), fullPage: true });
    }
    await manager.getByRole('button', { name: '关闭详情', exact: true }).focus(); await manager.keyboard.press('Enter');
    await manager.waitForURL(url => !url.searchParams.has('applicationId'));
    await waitFor(async () => manager.locator('.review-rows button').first().evaluate(node => node === document.activeElement));
    await manager.goBack(); await detailReady(manager);
    await manager.getByLabel('打开账号菜单').click(); await manager.keyboard.press('Escape');
    assert.equal(await manager.getByLabel('打开账号菜单').evaluate(node => node === document.activeElement), true);
    await manager.goto(`${adminUrl}?page=2`); await manager.getByText('当前页没有申请，请返回上一页或调整筛选。').waitFor();
    await manager.goto(`${adminUrl}?status=REJECTED&order=OLDEST`); await manager.getByRole('button', { name: /打开 resubmit/ }).waitFor();
    await manager.getByPlaceholder('申请编号、邮箱、手机号或内部账号').fill(`t17c1-${data.student}@example.invalid`);
    await manager.getByRole('button', { name: '查询', exact: true }).click(); await manager.getByText('没有符合条件的申请，可调整筛选或精确查询。').waitFor();
    assert.ok(!manager.url().includes('example.invalid'));
    report('real', 'deep link/refresh/back, filters/exact query/empty page, 360/768/1440 and keyboard focus');

    // 以下明确为Mock注入：真实身份保持，错误信封字段与OpenAPI一致，不宣称真实依赖故障。
    for (const status of [404, 422, 429, 500, 403, 401]) {
      const faultContext = await create(); const fault = await login(faultContext, 'admin', adminUrl);
      await fault.route(`**/api/v1/admin/teacher-applications/${first.id}`, route => route.fulfill({ status,
        headers: { 'Content-Type': 'application/json', ...(status === 429 ? { 'Retry-After': '1' } : {}) },
        body: faultBody() }));
      await fault.goto(`${adminUrl}?applicationId=${first.id}`); await fault.locator('[role=alert]').first().waitFor();
      assert.equal(await fault.locator('.statement').count(), 0); await faultContext.close();
      report('mock fault', `detail ${status} with private content cleared or partial list preserved`);
    }
    const mock = await create(); const unresolvedPage = await login(mock, 'admin', adminUrl);
    const fake = { ...latest, status: 'UNDER_REVIEW', rounds: latest.rounds.map(round => round.roundNumber === 2 ? { ...round, status: 'UNDER_REVIEW' } : round) };
    assert.ok(validateDetail(fake));
    await unresolvedPage.route(`**/api/v1/admin/teacher-applications/${second.id}`, route => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fake) }));
    const bodies = [];
    await unresolvedPage.route('**/decision', async route => { bodies.push(route.request().postDataJSON()); await route.fulfill({ status: 503, contentType: 'application/json', body: faultBody() }); });
    await unresolvedPage.goto(`${adminUrl}?applicationId=${second.id}`); await detailReady(unresolvedPage);
    await reject(unresolvedPage, 'mock_test_t17c1：保留未知原请求快照。'); await unresolvedPage.getByText('审核结果未决，请保留原请求核对，避免重复决定。').waitFor();
    for (let i = 0; i < 2; i++) { await unresolvedPage.getByRole('button', { name: '重试原决定', exact: true }).click(); await waitFor(async () => bodies.length === i + 2); }
    assert.equal(bodies.length, 3); assert.ok(bodies.every(body => JSON.stringify(body) === JSON.stringify(bodies[0])));
    await waitFor(async () => await unresolvedPage.getByRole('button', { name: '重试原决定', exact: true }).isDisabled());
    unresolvedPage.once('dialog', dialog => dialog.dismiss()); await unresolvedPage.getByRole('button', { name: '关闭详情', exact: true }).click();
    assert.equal(new URL(unresolvedPage.url()).searchParams.get('applicationId'), second.id);
    await unresolvedPage.unroute(`**/api/v1/admin/teacher-applications/${second.id}`);
    await mock.setOffline(true); await unresolvedPage.getByRole('button', { name: '刷新详情并核对', exact: true }).click();
    await unresolvedPage.getByText('网络不可用，请恢复网络后重试。', { exact: true }).waitFor(); await mock.setOffline(false);
    report('mock fault', 'unknown decision stable three retries, rejected leave and offline detail recovery');
  } catch (error) {
    for (let index = 0; index < contexts.length; index++) {
      const pages = contexts[index].pages();
      for (let number = 0; number < pages.length; number++) await pages[number].screenshot({ path: path.join(directory, `failure-${index}-${number}.png`), fullPage: true }).catch(() => {});
    }
    throw error;
  } finally {
    fs.writeFileSync(path.join(directory, 'browser-results.json'), JSON.stringify(results, null, 2));
    for (const context of contexts) await context.close().catch(() => {}); await browser.close();
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
