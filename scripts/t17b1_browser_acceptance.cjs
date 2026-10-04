/**
 * T17B.1 可重复真实浏览器验收：独立 Fixture + Gateway，发布/改版/撤回/确认均点真实页面。
 * 密码来自 DPAPI 调用器环境，Token 只在内存；报告仅含状态、UUID 和无秘密截图。
 * 额外的 HTTP 错误/离线/慢请求为明确标记的浏览器故障注入，不冒充真实服务故障。
 */
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const directory = path.resolve(__dirname, '../.m2/t17b1-browser');
const fixturePath = path.join(directory, 'fixture.json');
const data = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
const results = [];
const teacherUrl = `http://localhost:5174/teacher/courses/${data.course}/announcements`;
const courseUrl = `http://localhost:5173/courses/${data.course}`;
const tokens = {};
const workflowTitle = `T17B.1 教师学生闭环 ${crypto.randomUUID().slice(0, 8)}`;
const draftTitle = `我的保留输入 ${workflowTitle.slice(-8)}`;
const report = label => { results.push(label); console.log(`PASS ${label}`); };

/** 统一门户按真实表单登录，令牌从登录响应留在内存，用于 Gateway 事实核对；不保存 storageState。 */
async function login(context, name, target) {
  const page = await context.newPage();
  page.on('response', async response => {
    if (response.url().endsWith('/api/v1/auth/login') && response.status() === 200) tokens[name] = (await response.json()).accessToken;
  });
  const requested = new URL(target);
  await page.goto(`http://localhost:5173/login?redirect=${encodeURIComponent(requested.pathname + requested.search)}`);
  await page.getByPlaceholder('请输入已验证邮箱或手机号').fill(`t17b1-${data[name]}@example.invalid`);
  await page.getByPlaceholder('请输入密码').fill(process.env.T17B1_FIXTURE_PASSWORD);
  await page.getByRole('button', { name: '登录平台' }).click();
  await page.waitForURL(target, { timeout: 30000 }); return page;
}
/** 限时轮询真实事实；写命令不自动重试，以免把页面操作问题掩盖为脚本成功。 */
async function waitFor(check, timeout = 20000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await check()) return; await new Promise(resolve => setTimeout(resolve, 300)); }
  throw new Error('Bounded fact verification timeout');
}
async function api(context, name, method, endpoint, body, expected = 200) {
  const response = await context.request.fetch(`http://localhost:8080/api/v1${endpoint}`, {
    method, headers: { Authorization: `Bearer ${tokens[name]}` }, data: body
  });
  assert.equal(response.status(), expected, `${method} ${endpoint} expected ${expected}, received ${response.status()}`);
  if (response.status() === 204) return null;
  return response.json();
}
async function ready(page) { await page.getByRole('button', { name: '刷新', exact: true }).waitFor(); await waitFor(async () => !await page.getByRole('button', { name: '刷新', exact: true }).isDisabled()); }
async function screenshot(page, name) { await page.screenshot({ path: path.join(directory, name), fullPage: true }); }

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const contexts = [];
  try {
    const owner = await browser.newContext({ viewport: { width: 1440, height: 960 } }); contexts.push(owner);
    const student = await browser.newContext({ viewport: { width: 1440, height: 960 } }); contexts.push(student);
    const nonowner = await browser.newContext(); contexts.push(nonowner);
    const admin = await browser.newContext(); contexts.push(admin);
    const teacher = await login(owner, 'owner', teacherUrl); await ready(teacher);
    assert.equal(await teacher.getByRole('link', { name: '课程管理', exact: true }).getAttribute('aria-current'), 'page');
    assert.equal(await teacher.getByRole('link', { name: '工作台', exact: true }).count(), 0);
    assert.equal(await teacher.getByRole('link', { name: '媒体中心', exact: true }).count(), 0);
    await teacher.getByRole('button', { name: '下一页', exact: true }).click(); await teacher.waitForURL(/page=2/); await ready(teacher);
    await teacher.reload(); await ready(teacher); assert.match(await teacher.locator('[aria-label="公告分页"]').innerText(), /第 2/);
    await teacher.getByRole('link', { name: '返回课程管理' }).click();
    await teacher.waitForURL(/\/teacher\/courses\?page=1$/);
    await teacher.locator('.teacher-course-card').filter({ hasText: 'T17B.1 独立公告验收课程' }).getByRole('link', { name: '课程公告' }).click(); await ready(teacher);
    for (const source of ['edit', 'curriculum']) {
      await teacher.goto(`http://localhost:5174/teacher/courses/${data.course}/${source}`);
      await teacher.getByRole('link', { name: '课程公告', exact: true }).click(); await ready(teacher);
      assert.equal(new URL(teacher.url()).searchParams.get('from'), source);
      await teacher.getByRole('link', { name: source === 'edit' ? '返回基本信息' : '返回章节与课时' }).click();
      await teacher.waitForURL(new RegExp(`/${source}$`));
    }
    await teacher.goto(`${teacherUrl}?panel=audit&announcementId=${data.auditAnnouncement}&auditPage=2`); await ready(teacher);
    await teacher.getByRole('heading', { name: '只读审计：多版本审计验收公告' }).waitFor();
    await waitFor(async () => (await teacher.locator('[aria-label="审计版本分页"]').innerText()).includes('第 2'));
    assert.match(await teacher.locator('.announcement-detail').innerText(), /revision 1/); report('real pagination, refresh, course-card entry, deep-link audit page 2');

    await teacher.goto(teacherUrl); await ready(teacher);
    await teacher.getByRole('button', { name: '发布公告', exact: true }).click();
    await teacher.getByLabel('标题（最多 120 字符）').fill(workflowTitle);
    await teacher.getByLabel('正文（最多 20000 字符）').fill('初版纯文本正文\n<script>这只是文本</script>');
    const createdResponse = teacher.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith(`/teacher/courses/${data.course}/announcements`));
    await teacher.getByRole('button', { name: '确认立即发布', exact: true }).click();
    const created = await createdResponse; assert.equal(created.status(), 201);
    const announcement = await created.json(); data.announcement = announcement.id; fs.writeFileSync(fixturePath, JSON.stringify(data)); await ready(teacher);
    report('teacher UI immediate publish revision 1');

    const learner = await login(student, 'student', 'http://localhost:5173/notifications');
    await waitFor(async () => (await api(student, 'student', 'GET', '/notifications')).items.some(item => item.targetId === announcement.id && item.unread));
    await learner.reload();
    const message = learner.locator('.message-list li').filter({ hasText: workflowTitle });
    await message.getByRole('button', { name: '查看' }).click(); await learner.waitForURL(new RegExp(announcement.id));
    const studentArticle = learner.locator(`#announcement-${announcement.id}`); await studentArticle.getByRole('button', { name: '我知道了', exact: true }).waitFor();
    assert.match(await studentArticle.innerText(), /<script>这只是文本<\/script>/);
    const oldPage = await student.newPage(); await oldPage.goto(`${courseUrl}?announcementId=${announcement.id}`);
    await oldPage.locator(`#announcement-${announcement.id}`).getByRole('button', { name: '我知道了', exact: true }).waitFor();
    let studentFact = await api(student, 'student', 'GET', `/courses/${data.course}/announcements/${announcement.id}`); assert.equal(studentFact.confirmed, false);
    await studentArticle.getByRole('button', { name: '我知道了', exact: true }).click();
    await waitFor(async () => (await api(student, 'student', 'GET', `/courses/${data.course}/announcements/${announcement.id}`)).confirmed);
    assert.equal((await api(student, 'student', 'GET', '/notifications')).items.find(item => item.targetId === announcement.id).unread, false);
    report('student real message target + course text, viewing does not confirm, explicit confirm clears unread');

    await teacher.locator('.announcement-card').filter({ hasText: workflowTitle }).getByRole('button', { name: '改版', exact: true }).click();
    await teacher.getByLabel('正文（最多 20000 字符）').fill('第二版正文：需要再次确认。');
    const changedResponse = teacher.waitForResponse(response => response.request().method() === 'PUT' && response.url().includes(`/announcements/${announcement.id}`));
    await teacher.getByRole('button', { name: '确认发布改版' }).click(); const changed = await changedResponse; assert.equal(changed.status(), 200); assert.equal((await changed.json()).revision, 2); await ready(teacher);
    const staleResponse = oldPage.waitForResponse(response => response.request().method() === 'POST' && response.url().includes(`/announcements/${announcement.id}/confirm`));
    await oldPage.locator(`#announcement-${announcement.id}`).getByRole('button', { name: '我知道了', exact: true }).click();
    assert.equal((await staleResponse).status(), 409);
    await oldPage.getByText('公告内容已更新，请阅读最新内容后再次点击“我知道了”。', { exact: true }).waitFor();
    await oldPage.locator(`#announcement-${announcement.id}`).getByText('第二版正文：需要再次确认。', { exact: true }).waitFor();
    studentFact = await api(student, 'student', 'GET', `/courses/${data.course}/announcements/${announcement.id}`); assert.equal(studentFact.confirmed, false); assert.equal(studentFact.revision, 2);
    const currentMessages = (await api(student, 'student', 'GET', '/notifications')).items.filter(item => item.targetId === announcement.id);
    assert.equal(currentMessages.length, 1); assert.equal(currentMessages[0].unread, true);
    await oldPage.locator(`#announcement-${announcement.id}`).getByRole('button', { name: '我知道了', exact: true }).click();
    await waitFor(async () => (await api(student, 'student', 'GET', `/courses/${data.course}/announcements/${announcement.id}`)).confirmed);
    report('teacher UI revision 2; student stale revision real 409, reload without auto-confirm, explicit re-confirm; one message');

    for (const width of [360, 768, 1440]) {
      await teacher.setViewportSize({ width, height: 960 });
      await teacher.goto(`${teacherUrl}?panel=audit&announcementId=${announcement.id}`); await ready(teacher);
      await teacher.locator('[aria-label="审计版本分页"]').waitFor();
      const overflow = await teacher.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1); assert.equal(overflow, false, `overflow at ${width}`);
      await screenshot(teacher, `teacher-${width}.png`);
      await teacher.screenshot({ path: path.join(directory, `teacher-${width}-viewport.png`) });
    }
    await teacher.goto(`${teacherUrl}?panel=create`); await ready(teacher);
    await teacher.getByLabel('标题（最多 120 字符）').focus(); await teacher.keyboard.type('键盘未保存测试');
    await teacher.keyboard.press('Tab'); assert.equal(await teacher.getByLabel('正文（最多 20000 字符）').evaluate(node => node === document.activeElement), true);
    await teacher.keyboard.type('键盘正文');
    let rejected = false;
    teacher.once('dialog', async dialog => { rejected = true; await dialog.dismiss(); }); await teacher.getByRole('link', { name: '返回课程管理' }).click();
    assert.equal(rejected, true); assert.match(teacher.url(), /panel=create/);
    teacher.once('dialog', dialog => dialog.accept()); await teacher.getByRole('link', { name: '返回课程管理' }).click(); await teacher.waitForURL(/\/teacher\/courses\?page=1/);
    report('real 360/768/1440 responsive screenshots, keyboard title-to-body navigation, dirty leave reject/accept');

    await teacher.goto(`${teacherUrl}?panel=edit&announcementId=${announcement.id}`); await ready(teacher);
    await teacher.getByLabel('标题（最多 120 字符）').fill(draftTitle);
    await api(owner, 'owner', 'PUT', `/teacher/courses/${data.course}/announcements/${announcement.id}`, { clientRequestId: crypto.randomUUID(), expectedRevision: 2, title: '并发外部改版', body: '服务端第三版' });
    const ownerConflict = teacher.waitForResponse(response => response.request().method() === 'PUT' && response.url().includes(`/announcements/${announcement.id}`));
    await teacher.getByRole('button', { name: '确认发布改版' }).click(); assert.equal((await ownerConflict).status(), 409);
    await teacher.getByRole('button', { name: '采用最新 revision，保留我的输入' }).waitFor();
    assert.equal(await teacher.getByLabel('标题（最多 120 字符）').inputValue(), draftTitle);
    await waitFor(async () => !(await teacher.getByRole('button', { name: '采用最新 revision，保留我的输入' }).isDisabled()));
    await teacher.getByRole('button', { name: '采用最新 revision，保留我的输入' }).click();
    await teacher.getByRole('button', { name: '确认发布改版' }).click(); await ready(teacher);
    report('teacher real optimistic revision conflict preserves draft and requires explicit adoption');

    const outsider = await login(nonowner, 'nonowner', teacherUrl);
    await outsider.getByText('课程或公告不存在，或不属于当前教师。', { exact: false }).first().waitFor();
    assert.equal(await outsider.getByRole('button', { name: '发布公告', exact: true }).isDisabled(), true);
    await api(nonowner, 'nonowner', 'GET', `/teacher/courses/${data.course}/announcements`, undefined, 404);
    await login(admin, 'admin', 'http://localhost:5175/admin/dashboard');
    await api(admin, 'admin', 'POST', `/teacher/courses/${data.course}/announcements`, { clientRequestId: crypto.randomUUID(), title: '管理员不能代发', body: '拒绝测试' }, 403);
    report('real non-owner 404 and admin write 403; private text absent');

    // 下架由现有 owner 课程 API 写入；暂停使用管理员真实撤教学流程放在公告闭环之后。
    let course = await api(owner, 'owner', 'GET', `/teacher/courses/${data.course}`);
    await api(owner, 'owner', 'PUT', `/teacher/courses/${data.course}/publication`, { expectedRevision: course.revision, status: 'UNPUBLISHED' });
    await teacher.reload(); await ready(teacher); assert.equal(await teacher.getByRole('button', { name: '发布公告', exact: true }).isDisabled(), true);
    await api(owner, 'owner', 'POST', `/teacher/courses/${data.course}/announcements`, { clientRequestId: crypto.randomUUID(), title: '下架不能发布', body: '拒绝测试' }, 409);
    course = await api(owner, 'owner', 'GET', `/teacher/courses/${data.course}`);
    await api(owner, 'owner', 'PUT', `/teacher/courses/${data.course}/publication`, { expectedRevision: course.revision, status: 'PUBLISHED' });
    await teacher.reload(); await ready(teacher); report('real unpublished read history + disabled write + backend 409; restore only fixture course publication');

    const activeTitle = draftTitle;
    await teacher.locator('.announcement-card').filter({ hasText: activeTitle }).getByRole('button', { name: '选择撤回', exact: true }).click();
    await teacher.getByRole('button', { name: '撤回这条公告', exact: true }).click();
    assert.equal((await api(owner, 'owner', 'GET', `/teacher/courses/${data.course}/announcements`)).items.find(item => item.id === announcement.id).status, 'ACTIVE');
    await teacher.getByRole('button', { name: '确认撤回', exact: true }).click(); await ready(teacher);
    await api(student, 'student', 'GET', `/courses/${data.course}/announcements/${announcement.id}`, undefined, 404);
    await oldPage.goto(`${courseUrl}?announcementId=${announcement.id}`);
    await oldPage.getByText('内容不存在、已撤回或当前不可见。', { exact: false }).first().waitFor();
    assert.equal(await oldPage.locator(`#announcement-${announcement.id}`).count(), 0);
    report('teacher UI second confirmation withdrawal; student old link real 404 and no old body');

    // 以下为浏览器请求故障注入；仅合成错误响应，不能写入或放宽真实后端授权。
    const fault = await owner.newPage(); fault.on('dialog', dialog => dialog.accept());
    const cors = { 'Access-Control-Allow-Origin': 'http://localhost:5174', 'Access-Control-Allow-Credentials': 'true', 'Access-Control-Expose-Headers': 'Retry-After' };
    const listPattern = `**/api/v1/teacher/courses/${data.course}/announcements?*`;
    const errorBody = (status, fieldErrors = []) => ({ code: 'TEST.INJECTED', message: '合成故障', traceId: 'test-t17b1',
      timestamp: new Date().toISOString(), path: '/test', fieldErrors });
    await fault.goto(teacherUrl); await ready(fault);
    const textBefore = await fault.locator('.announcement-card').first().innerText();
    await owner.setOffline(true); await fault.getByRole('button', { name: '刷新', exact: true }).click();
    await fault.getByText('网络不可用，请恢复网络后重试。', { exact: false }).first().waitFor();
    assert.equal(await fault.locator('.announcement-card').first().innerText(), textBefore);
    await owner.setOffline(false); await fault.getByRole('button', { name: '刷新', exact: true }).click(); await ready(fault);
    report('browser fault injection: offline preserves successful list, restoring network allows manual refresh');

    await fault.route(listPattern, route => route.fulfill({ status: 200, contentType: 'application/json', headers: cors,
      body: JSON.stringify({ items: [], page: 1, size: 20, total: 0, latestUnconfirmed: null, unconfirmedCount: 0 }) }));
    await fault.getByRole('button', { name: '刷新', exact: true }).click(); await fault.getByText('这一页暂无公告。', { exact: false }).waitFor();
    await fault.unroute(listPattern); await fault.goto(`${teacherUrl}?panel=audit&announcementId=${data.course}`); await ready(fault);
    await fault.getByText('选中公告不在当前已载分页。', { exact: false }).waitFor();
    await fault.goto(teacherUrl); await ready(fault);
    let delayed = false; let aborted = false;
    fault.on('requestfailed', request => { if (request.url().includes(`/teacher/courses/${data.course}/announcements`)) aborted = true; });
    await fault.route(listPattern, async route => {
      delayed = true; await new Promise(resolve => setTimeout(resolve, 800));
      try { await route.continue(); } catch { /* 取消后的请求没有页面回写。 */ }
    });
    await fault.getByRole('button', { name: '刷新', exact: true }).click(); await waitFor(async () => delayed);
    await fault.getByText('正在读取课程状态和公告…', { exact: true }).waitFor();
    await fault.getByRole('link', { name: '返回课程管理' }).click(); await fault.waitForURL(/\/teacher\/courses\?page=1/);
    await waitFor(async () => aborted); await fault.unroute(listPattern); await fault.goto(teacherUrl); await ready(fault);
    report('browser fault injection: loading/empty, bounded unlocated selection, slow request aborted on leaving');

    let limitedCalls = 0;
    await fault.route(listPattern, route => { limitedCalls++; return route.fulfill({ status: 429, contentType: 'application/json',
      headers: { ...cors, 'Retry-After': '1' }, body: JSON.stringify(errorBody(429)) }); });
    await fault.getByRole('button', { name: '刷新', exact: true }).click();
    await fault.getByText('请求过于频繁，请至少等待 1 秒后重试。', { exact: false }).first().waitFor();
    assert.equal(await fault.getByRole('button', { name: '重试列表' }).isDisabled(), true);
    await waitFor(async () => !(await fault.getByRole('button', { name: '重试列表' }).isDisabled())); assert.equal(limitedCalls, 1);
    await fault.unroute(listPattern); await fault.getByRole('button', { name: '刷新', exact: true }).click(); await ready(fault);
    report('browser fault injection: 429 Retry-After respected without automatic retries');

    const postPattern = `**/api/v1/teacher/courses/${data.course}/announcements`;
    await fault.goto(`${teacherUrl}?panel=create`); await ready(fault);
    await fault.getByLabel('标题（最多 120 字符）').fill('故障注入草稿'); await fault.getByLabel('正文（最多 20000 字符）').fill('输入保持');
    await fault.route(postPattern, route => route.request().method() === 'POST' ? route.fulfill({ status: 422, contentType: 'application/json', headers: cors,
      body: JSON.stringify(errorBody(422, [{ field: 'title', reason: '注入字段错误' }, { field: 'request', reason: '注入请求错误' }])) }) : route.continue());
    await fault.getByRole('button', { name: '确认立即发布' }).click(); await fault.getByText('request：注入请求错误', { exact: true }).waitFor();
    assert.equal(await fault.getByLabel('标题（最多 120 字符）').inputValue(), '故障注入草稿');
    await fault.unroute(postPattern);
    const uncertainIds = [];
    await fault.route(postPattern, route => {
      if (route.request().method() !== 'POST') return route.continue();
      uncertainIds.push(route.request().postDataJSON().clientRequestId);
      return route.fulfill({ status: 503, contentType: 'application/json', headers: cors, body: JSON.stringify(errorBody(503)) });
    });
    for (let attempt = 0; attempt < 2; attempt++) {
      await fault.getByRole('button', { name: '确认立即发布' }).click();
      await fault.getByText('写入结果尚不确定', { exact: false }).waitFor();
      await waitFor(async () => !(await fault.getByRole('button', { name: '确认立即发布' }).isDisabled()));
    }
    assert.equal(uncertainIds[0], uncertainIds[1]); await fault.unroute(postPattern);
    report('browser fault injection: 422 field + request errors and 503 unknown outcome retain draft and stable ID');

    await fault.goto(teacherUrl); await ready(fault);
    await fault.route(listPattern, route => route.fulfill({ status: 403, contentType: 'application/json', headers: cors, body: JSON.stringify(errorBody(403)) }));
    await fault.getByRole('button', { name: '刷新', exact: true }).click();
    await fault.getByText('当前账号没有公告访问权限。', { exact: false }).first().waitFor(); assert.equal(await fault.locator('.announcement-card').count(), 0);
    await fault.unroute(listPattern); await fault.goto(teacherUrl); await ready(fault);
    await fault.route(listPattern, route => route.fulfill({ status: 401, contentType: 'application/json', headers: cors, body: JSON.stringify(errorBody(401)) }));
    await fault.getByRole('button', { name: '刷新', exact: true }).click();
    await fault.getByText('登录状态已失效，请重新登录。', { exact: false }).first().waitFor(); assert.equal(await fault.locator('.announcement-card').count(), 0);
    await fault.close(); report('browser fault injection: 403/401 clear protected text; requests and contexts closed');

    const preview = await api(admin, 'admin', 'POST', `/admin/users/${data.owner}/permission-previews`, { action: 'REVOKE_TEACHING' }, 201);
    const decision = await api(admin, 'admin', 'POST', `/admin/users/${data.owner}/permission-commands`, {
      clientRequestId: crypto.randomUUID(), previewId: preview.previewId, expectedRevision: preview.expectedRevision,
      action: 'REVOKE_TEACHING', reason: 'test_t17b1 独立临时课程暂停验收', confirmed: true
    }, 202);
    await waitFor(async () => (await api(admin, 'admin', 'GET', `/admin/permission-commands/${decision.commandId}`)).coordinationStatus === 'APPLIED');
    await teacher.reload(); await teacher.getByPlaceholder('请输入已验证邮箱或手机号').waitFor();
    await teacher.getByPlaceholder('请输入已验证邮箱或手机号').fill(`t17b1-${data.owner}@example.invalid`);
    await teacher.getByPlaceholder('请输入密码').fill(process.env.T17B1_FIXTURE_PASSWORD);
    await teacher.getByRole('button', { name: '登录平台' }).click();
    await teacher.getByText('无权访问此应用', { exact: false }).first().waitFor();
    report('real revoke teaching + Course pause coordination; refreshed owner cannot enter announcement writes');
  } finally {
    fs.writeFileSync(path.join(directory, 'browser-results.json'), JSON.stringify({ completed: results, count: results.length }, null, 2));
    for (const context of contexts) await context.close(); await browser.close();
  }
})().catch(error => { console.error(`T17B.1 browser acceptance failed: ${error.message}`); process.exitCode = 1; });
