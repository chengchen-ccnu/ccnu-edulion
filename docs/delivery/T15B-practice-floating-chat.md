# T15B 普通自主练习悬浮答疑交付

## 任务目标与边界

在学生端受保护的 `/practice/questions/:questionId` 公共题详情页增加 AI 悬浮答疑：桌面固定右下角按钮与面板，手机使用近全屏承载。会话跨题连续、空闲 30 分钟后下次发送换新，活动运行不因超时中断；未发送草稿保留原题关联并允许显式切换当前题。

本任务不在课程播放器、HOMEWORK、EXAM、结果页或错题本增加 AI 入口，不提交答案、不判分、不修改学习进度，也不实现自主组题、画像或第二套会话协议。

## 架构与调用流程

1. 题目页只传入 Learning 返回的 `SystemQuestion` 安全投影；打开面板先读取最近会话，不创建空会话。
2. 首次发送或最近悬浮会话已空闲 30 分钟时，创建 `PRACTICE_FLOATING` 会话；空闲时间优先取最近 run 的 `FINAL_RESULT.occurredAt`，没有 run 才回退最近有效聊天时间。每次发送都附带当前草稿固定的 `questionId` 和最多 8 个 `QUESTION_IMAGE mediaId`，不携带答案或解析。
3. SSE、停止、断线恢复、部分回答、安全 Markdown/公式渲染及重新生成复用 T15A 实现；409 显式提示同会话已有运行。
4. 历史 USER 消息默认折叠“附带题目”，展开后才渲染 OpenAPI 0.23.3 的精确 `questionContextProjection` 并加载题图。题图通过 conversation/message/version/media 绑定授权并复用共享 Blob 缓存；`UNAVAILABLE` 不回退公共题当前版本。完整 AI 页面复用同一历史题目卡，不把公共题图误当成用户私有上传。
5. 关闭面板、离开题页或转完整页会以 `PAGE_CLOSED` 取消活动运行，再读取已保存事件和消息；组件卸载同时中止生命周期请求、SSE、Blob 与键盘监听器。Python 运行时在接受取消后原子释放会话槽位，旧流收尾不会误删新 run，确保同一悬浮会话可立即继续提问。

真实联调发现既有 Agent Access 图片上下文路径存在一个阻断：Learning 的访问授权响应不含业务用途，Java record 主构造器得到 `purpose=null` 后在可信上下文组装时触发 NPE。`HttpAuthorizedContextResolver` 现将缺省用途归一为 `QUESTION_IMAGE`，AI 私有图片的显式 `AI_QUESTION_IMAGE` 不受影响，并补充回归测试。

## API、数据与迁移

未修改 `docs/api/openapi.yaml`、事件 Schema、数据库迁移或表结构。前端只消费既有生成接口：会话列表/创建/详情、消息分页、发送与重新生成 SSE、运行事件/取消，以及消息绑定题图访问授权。

没有新建数据库、表、Seed、账号、题目或媒体资产。自动化使用 jsdom 与 Agent Access H2 内存库，进程结束自动销毁。真实验收复用既有 `student_test_01`、两个 ACTIVE SYSTEM 含图题和用途明确的本地 dev Schema；曾创建 1 个 `PRACTICE_FLOATING` 会话、6 条消息和 3 个 run（CANCELLED、缺陷复现时 FAILED、修复后 COMPLETED 各 1 个），以及 36 条本轮题图访问授权、6 个精确 Redis run/idempotency key 和 24000 个本轮预留 Token 计数。它们已按精确 conversation/run/message/grant/key 逆依赖删除或扣回，Agent/Media/Redis 本轮残留均为 0；既有日额度仍保留 32000，没有创建 OSS 对象，也没有新增刷新会话或登录审计。重新生成验收数据的方法是按下文启动服务，以固定测试身份重新发送一条问题；清理必须再次记录本轮精确 ID，不得按用户整表删除。

## 环境与启动

没有新增环境变量，也没有修改 `local.env`；本轮没有需要从 `local.env` 删除或迁移到其他位置的配置。Java 服务必须经 `scripts/Invoke-WithLocalEnvironment.ps1` 注入本地加密凭据，Python 使用项目已有 `langgraph` Python 3.11 环境，前端使用 `VITE_GATEWAY_BASE_URL=http://localhost:8080` 与 `VITE_STUDENT_WEB_URL=http://localhost:5173`。OSS CORS 的本地允许 Origin 是 `localhost`；使用 `127.0.0.1` 会得到题图网络失败，不能据此判定业务授权失败。

## 测试与实际结果

- `pnpm.cmd --dir web-apps run generate:api`：成功，OpenAPI 0.23.3 客户端重新生成。
- `pnpm.cmd --dir web-apps run typecheck`：8 个 workspace 全部通过。
- `pnpm.cmd --dir web-apps run test`：49 个测试文件、330 项全部通过。
- `pnpm.cmd --dir web-apps run build`：admin/student/teacher 三端全部通过；仅保留既有大 chunk 警告。
- `D:\Anaconda\envs\langgraph\python.exe -m unittest discover -s tests -v`：66 项通过，9 项需显式真实环境开关而跳过。
- `mvn --% -Dmaven.repo.local=.m2/repository -f java-platform/pom.xml clean verify`：9 个 Reactor 模块全部成功，270 项执行、235 项通过、35 项真实环境开关跳过；Agent Access 47 项中 44 项通过、3 项跳过。
- 定向前端测试：4 个文件、23 项通过；覆盖懒创建、精确终态时间的 30 分钟边界、活动运行、草稿原关联/切换、精确历史投影、折叠时不加载题图、消息绑定题图授权、UNAVAILABLE、完整页恢复、生命周期中止、键盘焦点和页面边界。Python 定向取消回归 1 项通过。

## 人工演示与真实验收

1. 经 Gateway 真实登录 `student1@example.invalid`，打开 `http://localhost:5173/practice/questions/cabc1bc7-d2f8-427a-ac9d-89db22726599`，确认两张真实 OSS 题图可见且页面只有右下角“AI 答疑”入口。
2. 打开面板，确认不产生空会话；首次发送“请只给我一个解题提示，不要直接给答案”，真实 Gateway → Agent Access → Learning/Media/OSS → Python/模型 → Redis 链路启动生成，关闭面板后得到唯一 CANCELLED 终态和已保存的“回答已中断”。
3. 消息历史默认折叠题目；展开后显示发送时的题型、题干和题图。桌面与 390×844 手机近全屏布局无第二套页面，Escape 关闭并把焦点返回“AI 答疑”按钮。
4. 切换到第二个含七张题图的公共题时保留同一会话和旧题消息；取消旧 run 后在同一 conversation 成功完成新一轮。转完整页后从服务端恢复三组消息，展开最新消息可见精确第二题投影与七张 Blob 题图。

真实浏览器完成以上链路，并验证 390×844 手机近全屏、安全区和无横向滚动；桌面关闭后焦点返回按钮。全部页面题图与历史题图 URL 均为 `blob:`，localStorage/sessionStorage 均为空，浏览器 console error 为 0。30 分钟、多标签 409、离线与错误映射由确定性自动化覆盖。

## 可读性、风险与后续依赖

新增 Vue 组件、状态判断、历史安全投影和 Java 默认用途均有中文职责/失败语义注释；人工检查未发现把实现逐字翻译的无效注释。生产仍需在正式域名复核 OSS CORS、Gateway 长连接超时和额度配置；空闲 30 分钟是前端自动沿用策略，不删除历史，也不替代服务端并发事实。T14.3/T15C 后续可在独立任务实现自主组题建议，不得扩展本悬浮窗的提交或评分职责。

## 分支与交付

- 分支：`codex/t15b-practice-floating-chat`
- 基线：`main@d544840`
- 规划提交：`acad0db`
- 实现提交：`4e19c79`
- 验收补强提交：`49c8d8e`
- Ready PR：[#67](https://github.com/chengchen-ccnu/ccnu-edulion/pull/67)，目标为 `main`，保持 Open 且不在本任务自动合并。
