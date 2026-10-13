/** T17C.2真实浏览器验收：资格/课程只经正常API，权限动作经页面；同契约Mock故障独立标记。
 * 密码由DPAPI包装器注入，JWT仅在内存；只保存无秘密UUID清单、结果与合成页面截图。
 */
const { chromium } = require('playwright');
const fs = require('node:fs'); const path = require('node:path'); const assert = require('node:assert/strict'); const crypto = require('node:crypto');
const directory = path.resolve(__dirname, '../.m2/t17c2-browser');
const YAML = require('../web-apps/node_modules/yaml'); const Ajv = require('../web-apps/node_modules/ajv/dist/2020').default;
const addFormats = require('../web-apps/node_modules/ajv-formats');
const contract = YAML.parse(fs.readFileSync(path.resolve(__dirname, '../docs/api/openapi.yaml'), 'utf8')); assert.equal(contract.info.version, '0.31.1');
const ajv = new Ajv({ strict: false }); addFormats(ajv); ajv.addSchema({ $id: 'edu', components: contract.components });
const validators = Object.fromEntries(['ApiError','AdminUser','AdminUserPage','AccountPermissionPreview','AccountPermissionCommand'].map(name => [name, ajv.compile({ $ref: `edu#/components/schemas/${name}` })]));
const data = JSON.parse(fs.readFileSync(path.join(directory, 'fixture.json'), 'utf8')); const tokens = {}; const results = [];
const adminUrl = 'http://localhost:5173/admin/users';
function save() { fs.writeFileSync(path.join(directory, 'fixture.json'), JSON.stringify(data)); }
function report(kind, label) { results.push({ kind, label }); console.log(`PASS [${kind}] ${label}`); }
function checked(name, body) { assert.ok(validators[name](body), JSON.stringify(validators[name].errors)); return JSON.stringify(body); }
function errorBody() { return checked('ApiError', { code: 'TEST.FAULT', message: '合成故障', traceId: 'synthetic', timestamp: new Date().toISOString(), path: '/synthetic', fieldErrors: [{field:'reason',reason:'合成字段错误'}] }); }
async function waitFor(check, timeout = 30000) { const until = Date.now() + timeout; while (Date.now() < until) { if (await check()) return; await new Promise(resolve => setTimeout(resolve, 200)); } throw new Error('Bounded acceptance condition timeout'); }
/** 通过唯一统一登录进入深链，尊重429冷却；Token只用于独立服务端事实核对。 */
async function login(context, name, target) {
  const page = await context.newPage();
  page.on('response', async response => { if (response.url().endsWith('/auth/login') && response.status() === 200) tokens[name] = (await response.json()).accessToken; });
  const url = new URL(target); await page.goto(`http://localhost:5174/login?redirect=${encodeURIComponent(url.pathname + url.search)}`);
  await page.getByPlaceholder('请输入已验证邮箱或手机号').fill(`t17c2-${data[name]}@example.invalid`);
  await page.getByPlaceholder('请输入密码').fill(process.env.T17C2_FIXTURE_PASSWORD);
  for (let attempt = 0; attempt < 3; attempt++) {
    const pending = page.waitForResponse(response => response.url().endsWith('/auth/login') && response.request().method() === 'POST');
    await page.getByRole('button', { name: '登录平台' }).click(); const response = await pending;
    if (response.status() === 200) break;
    assert.equal(response.status(), 429); const seconds = Math.max(1, Number(response.headers()['retry-after']) || 30);
    await new Promise(resolve => setTimeout(resolve, seconds * 1000 + 1000));
  }
  await page.waitForURL(target); if (url.pathname === '/admin/users') await ready(page); return page;
}
async function ready(page) { await waitFor(async () => !await page.getByText(/正在读取|正在查询/).count() && !!await page.locator('.account-list').count()); }
/** 正式Gateway HTTP只打印状态，不输出Token、密码、正文或内部响应。 */
async function api(context, name, method, endpoint, body, expected = 200) {
  const response = await context.request.fetch(`http://localhost:8080/api/v1${endpoint}`, { method, headers: { Authorization: `Bearer ${tokens[name]}` }, data: body });
  assert.equal(response.status(), expected, `${method} ${endpoint}: ${response.status()}`);
  return expected === 204 ? null : response.json();
}
/** 独立HTTP登录不写浏览器Cookie，防止验收管理员context被合成学生身份替换。 */
async function relogin(context, name) { const result = await fetch('http://localhost:8080/api/v1/auth/login', { method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({identity:`t17c2-${data[name]}@example.invalid`,password:process.env.T17C2_FIXTURE_PASSWORD}) }); assert.equal(result.status,200); tokens[name]=(await result.json()).accessToken; }
/** 正常学生上传READY、提交，再由管理员取得原件并批准；绝不伪造APPROVED或授能表。 */
async function qualify(context, learner) {
  await learner.locator('input[type=file]').setInputFiles(path.join(directory, 'qualification.png'));
  await learner.getByPlaceholder('请说明你的教学经历和申请理由（20～2000 字）').fill('student_test_t17c2 合成教学资格，仅供正常审核及权限课程闭环验收。');
  const submit = learner.getByRole('button', { name: '提交申请', exact: true }); await waitFor(async () => !await submit.isDisabled(), 60000);
  const pending = learner.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/teacher-applications'));
  await submit.click(); const application = await (await pending).json(); data.application = application.id; save();
  const detail = await api(context, 'admin', 'GET', `/admin/teacher-applications/${application.id}`);
  const grant = await api(context, 'admin', 'POST', `/admin/teacher-applications/${application.id}/material-access`, { roundId: detail.rounds[0].id, action: 'PREVIEW' }, 201);
  const material = await context.request.get(grant.url); assert.equal(material.status(), 200); assert.ok((await material.body()).length > 0);
  await api(context, 'admin', 'POST', `/admin/teacher-applications/${application.id}/decision`, { clientRequestId: crypto.randomUUID(), expectedRevision: detail.revision, decision: 'APPROVED', materialConfirmed: true });
  await relogin(context, 'student'); report('real', 'normal Media/OSS qualification upload and authorized approval, no fabricated teaching grant');
}
/** 正常Course API建立两发布课与一草稿，两个报名共用一学生用于真实去重人数验证。 */
async function courses(context) {
  const categories = await api(context, 'student', 'GET', '/courses/categories'); const categoryId = categories[0].id;
  for (const key of ['courseA','courseB','courseDraft']) {
    let course = await api(context, 'student', 'POST', '/teacher/courses', { clientRequestId: crypto.randomUUID(), title: `T17C.2 ${key} 临时验收`, summary: '合成权限验收', description: '仅本轮正常API创建，按UUID精确清理。', coverMediaId: null, instructorDisplayName: 'student_test_t17c2', categoryId, level: 'BEGINNER', accessType: 'PUBLIC' }, 201);
    data[key] = course.id; save();
    if (key === 'courseDraft') continue;
    course = await api(context, 'student', 'PUT', `/teacher/courses/${course.id}/curriculum`, { expectedRevision: course.revision, chapters: [{ title: '临时图文章节', sequence: 1, lessons: [{ title: '合成课时', type: 'ARTICLE', sequence: 1, previewable: false, articleMarkdown: '# 合成课程\n仅供权限协调验收。', resources: [] }] }] });
    await api(context, 'student', 'PUT', `/teacher/courses/${course.id}/publication`, { expectedRevision: course.revision, status: 'PUBLISHED' });
    const enrolled = await api(context, 'resubmit', 'POST', '/learning/enrollments', { courseId: course.id }, 201); data[key + 'Enrollment'] = enrolled.id; save();
  }
  report('real','two published courses and one draft via Course APIs, one ACTIVE student enrolled twice');
}
async function previewPage(page, label, expectedCourses, expectedStudents) {
  await page.getByRole('button', { name: '权限操作', exact: true }).click();
  await page.getByRole('button', { name: `预览${label}`, exact: true }).click();
  await page.locator('.account-preview').waitFor();
  assert.ok((await page.locator('.account-preview').innerText()).includes(`${expectedCourses} 门课程 · ${expectedStudents} 名去重 ACTIVE 学生`));
  await page.getByLabel('变更理由（10～500字）').fill('test_t17c2 合成权限治理验收，已核对真实课程与学生影响。');
  await page.getByRole('checkbox').check();
}
async function submitPage(page, label) {
  page.once('dialog', dialog => dialog.accept());
  const pending = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/permission-commands'));
  await page.getByRole('button', { name: `确认并提交${label}`, exact: true }).click(); const response = await pending; assert.equal(response.status(),202); return response.json();
}
async function settleCommand(page, context, name, command) {
  await waitFor(async () => (await api(context, name, 'GET', `/admin/permission-commands/${command.commandId}`)).coordinationStatus !== 'PENDING');
  await page.getByRole('button', { name: '刷新账号与最近命令并核对', exact: true }).click(); await ready(page);
  const fact = await api(context, name, 'GET', `/admin/permission-commands/${command.commandId}`);
  assert.ok(['APPLIED','NOT_REQUIRED'].includes(fact.coordinationStatus)); return fact;
}
async function oldTokenRejected(context, token) {
  const response = await context.request.get('http://localhost:8080/api/v1/users/me', { headers: { Authorization: `Bearer ${token}` } }); assert.equal(response.status(),401);
}

(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' }); const contexts = [];
  try {
    const create = async () => { const context = await browser.newContext({ viewport: { width:1440,height:960 } }); contexts.push(context); return context; };
    const admin = await create(), competitor = await create(), teacher = await create(), student = await create();
    const owner = await login(teacher,'student','http://localhost:5174/apply-teacher');
    const learner = await login(student,'resubmit','http://localhost:5174/my-learning');
    const page = await login(admin,'admin',`${adminUrl}?userId=${data.student}&panel=detail`);
    const second = await login(competitor,'competitor',`${adminUrl}?userId=${data.student}&panel=detail`);
    assert.equal(await api(admin,'admin','GET',`/admin/users/${data.student}/permission-commands/latest`,undefined,204),null);
    await qualify(admin,owner); await courses(admin); await page.reload(); await ready(page);
    await previewPage(page,'撤销教学能力',2,1); const oldTeacher = tokens.student; const revoked = await submitPage(page,'撤销教学能力');
    await settleCommand(page,admin,'admin',revoked); await relogin(admin,'student'); await oldTokenRejected(admin,oldTeacher);
    const unavailable = await admin.request.get(`http://localhost:8080/api/v1/courses/${data.courseA}/announcements`,{headers:{Authorization:`Bearer ${tokens.resubmit}`}}); assert.equal(unavailable.status(),404);
    report('real','page preview deduplicates one student; revoke commits then Course pauses; old teaching session rejected');
    await previewPage(page,'恢复教学能力',2,1); const restored = await submitPage(page,'恢复教学能力'); await settleCommand(page,admin,'admin',restored);
    const restoredUser = await api(admin,'admin','GET',`/admin/users/${data.student}`); assert.ok(restoredUser.permissions.includes('teaching:access')); assert.ok(restoredUser.revision > restored.decidedRevision);
    await relogin(admin,'student'); report('real','RESTORE_TEACHING re-reads async revision and actual teaching capability after Course confirmation');
    await previewPage(page,'封禁账号',2,1); const bannedToken = tokens.student; const banned = await submitPage(page,'封禁账号'); await settleCommand(page,admin,'admin',banned);
    const bannedLogin = await admin.request.post('http://localhost:8080/api/v1/auth/login',{data:{identity:`t17c2-${data.student}@example.invalid`,password:process.env.T17C2_FIXTURE_PASSWORD}}); assert.equal(bannedLogin.status(),403);
    await previewPage(page,'解封账号',0,0); const unbanned = await submitPage(page,'解封账号'); await settleCommand(page,admin,'admin',unbanned);
    const ordinary = await api(admin,'admin','GET',`/admin/users/${data.student}`); assert.deepEqual(ordinary.roles,['STUDENT']); await relogin(admin,'student'); await oldTokenRejected(admin,bannedToken);
    await previewPage(page,'恢复教学能力',2,1); const restoredAgain = await submitPage(page,'恢复教学能力'); await settleCommand(page,admin,'admin',restoredAgain);
    report('real','BAN/UNBAN invalidates sessions; UNBAN restores student only, teaching restores separately in original cycle');
    // 两管理员先读同版本；原账号不会参与封禁测试。
    await page.goto(`${adminUrl}?userId=${data.resubmit}&panel=detail`); await second.goto(`${adminUrl}?userId=${data.resubmit}&panel=detail`); await ready(page); await ready(second);
    await previewPage(page,'封禁账号',0,0); await previewPage(second,'封禁账号',0,0); await submitPage(page,'封禁账号');
    second.once('dialog',dialog=>dialog.accept()); const race = second.waitForResponse(response=>response.request().method()==='POST'&&response.url().endsWith('/permission-commands'));
    await second.getByRole('button',{name:'确认并提交封禁账号',exact:true}).click(); assert.equal((await race).status(),409); await second.getByText('请先点击“刷新账号与最近命令并核对”，之后重新预览。').waitFor();
    await page.getByRole('button',{name:'刷新账号与最近命令并核对',exact:true}).click(); await ready(page); await previewPage(page,'解封账号',0,0); await submitPage(page,'解封账号'); await ready(page);
    await api(admin,'admin','POST',`/admin/users/${data.admin}/permission-previews`,{action:'BAN'},409);
    report('real','two-administrator stale preview receives 409, explicit review required; server self-ban guard remains active');
    // 上游确实202后切断浏览器回执；另一管理员只知道userId，经latest恢复公开commandId。
    await page.goto(`${adminUrl}?userId=${data.resubmit}&panel=detail`); await ready(page); await previewPage(page,'封禁账号',0,0);
    await page.route(`**/api/v1/admin/users/${data.resubmit}/permission-commands`,async route=>{const response=await route.fetch();assert.equal(response.status(),202);await route.abort('failed');});
    page.once('dialog',dialog=>dialog.accept()); await page.getByRole('button',{name:'确认并提交封禁账号',exact:true}).click(); await page.getByText(/写入结果未知/).waitFor();
    await second.goto(`${adminUrl}?userId=${data.resubmit}&panel=coordination`); await ready(second);
    const recovered = await api(competitor,'competitor','GET',`/admin/users/${data.resubmit}/permission-commands/latest`); assert.ok((await second.locator('.account-coordination').innerText()).includes(recovered.commandId));
    await previewPage(second,'解封账号',0,0); const recoveredUnban = await submitPage(second,'解封账号'); await settleCommand(second,competitor,'competitor',recoveredUnban);
    await page.unrouteAll(); page.once('dialog',dialog=>dialog.accept()); await page.reload(); await ready(page);
    report('real','202 receipt lost at browser, unknown-state protection, second administrator recovers latest ID, refresh restores facts');
    // 响应式及键盘均在真实账号详情上验证，保持两项侧栏与账号工具顺序。
    for(const width of [360,768,1440]){await page.setViewportSize({width,height:960});await ready(page);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));await page.screenshot({path:path.join(directory,`accounts-${width}.png`),fullPage:true});}
    await page.getByRole('button',{name:'关闭详情',exact:true}).focus(); await page.keyboard.press('Enter'); await page.waitForURL(adminUrl); await page.goBack(); await ready(page);
    await page.locator('.account-detail').focus(); await page.keyboard.press('Escape'); await page.waitForURL(adminUrl);
    report('real','360/768/1440 layout no horizontal overflow; Enter/Escape, deep link, refresh and browser back');
    // 合成学生经历BAN/UNBAN后旧Refresh已撤销，必须经正式登录取得当前会话再验证403。
    const ordinaryPage=await login(student,'resubmit','http://localhost:5174/my-learning');
    await ordinaryPage.goto(`${adminUrl}?userId=${data.student}`); await ordinaryPage.getByRole('heading',{name:'无权访问此应用'}).waitFor();
    await api(student,'resubmit','GET',`/admin/users/${data.student}`,undefined,403); report('real','non-administrator denied by frontend guard and Gateway/IAM');
    // 同OpenAPI校验的HTTP故障不冒充真实数据库/领域故障。
    await page.goto(`${adminUrl}?userId=${data.student}&panel=detail`); await ready(page);
    const safeUser = await api(admin,'admin','GET',`/admin/users/${data.student}`); checked('AdminUser',safeUser);
    for(const status of [404,422,429,503]){
      await page.route(`**/api/v1/admin/users/${data.student}/permission-commands/latest`,route=>route.fulfill({status,contentType:'application/json',headers:status===429?{'Retry-After':'1'}:{},body:errorBody()}));
      await page.reload();await ready(page);assert.ok(await page.locator('.account-rows').count());assert.ok(await page.locator('.panel-error').count());
      await page.unrouteAll(); if(status===429)await new Promise(resolve=>setTimeout(resolve,1100)); report('mock',`latest ${status} partial error, list retained, mutation unavailable`);
    }
    const real = await api(admin,'admin','GET',`/admin/users/${data.student}/permission-commands/latest`);
    for(const attempts of [3,6]){
      const synthetic={...real,coordinationStatus:'FAILED',attempts};const body=checked('AccountPermissionCommand',synthetic);
      await page.route(`**/api/v1/admin/users/${data.student}/permission-commands/latest`,route=>route.fulfill({status:200,contentType:'application/json',body}));
      await page.route(`**/api/v1/admin/permission-commands/${real.commandId}`,route=>route.fulfill({status:200,contentType:'application/json',body}));
      await page.reload();await ready(page);assert.equal(await page.getByRole('button',{name:'补偿原命令一次',exact:true}).isDisabled(),attempts===6);
      await page.unrouteAll();report('mock',`FAILED ${attempts}/6 shows server budget; no automatic compensation`);
    }
    await page.reload();await ready(page);await admin.setOffline(true);await page.getByText(/当前离线/).waitFor();assert.ok(await page.getByRole('button',{name:'刷新列表',exact:true}).isDisabled());await admin.setOffline(false);report('mock','browser offline cancels polling and disables mutations');
    for(const status of [401,403]){
      const isolated=await create();const fault=await login(isolated,'admin',`${adminUrl}?userId=${data.student}`);
      await fault.route('**/api/v1/admin/users**',route=>route.fulfill({status,contentType:'application/json',body:errorBody()}));await fault.getByRole('button',{name:'刷新列表',exact:true}).click();
      await fault.getByText('登录失效或当前账号没有管理权限，请重新登录。',{exact:true}).first().waitFor();assert.equal(await fault.locator('.account-rows').count(),0);report('mock',`${status} clears private account data and controls`);
    }
  } finally { fs.writeFileSync(path.join(directory,'browser-results.json'),JSON.stringify(results,null,2));for(const context of contexts)await context.close();await browser.close(); }
})().catch(error=>{console.error(error.message);process.exitCode=1;});
