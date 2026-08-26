# T12A learning progress web delivery

## 任务目标与边界

T12A 启用学生端 `/my-learning`，在公共课程详情接通真实报名、报名状态和开始/继续学习，并在既有 `/learn/:courseId/:lessonId` 媒体学习页增加真实单调进度与本人学习笔记。学习空间统一按 `learning:access` 能力授权，不要求 `STUDENT` 角色，因此同时拥有 `learning:access` 与 `teaching:access` 的教师型账号可同时进入学习空间和教学工作台。

本任务没有创建第二套学习路由、移动播放器或把播放器放回课程详情，也没有实现 T12B 测评、T12C 教师学情、T15 AI、T17 门户/通知/资料。`/my-learning` 规格中的“收藏”目前没有 OpenAPI 契约；按“不猜字段、不做假数据”规则，本次不展示伪收藏入口，等待后续先补契约再实现。

## 架构与调用流程

- 浏览器仍只访问 Gateway。`StudentLearningApi` 复用 `createGatewayClient()` 和统一 `authorizedRequest()`，报名、进度和笔记输入输出均直接引用 OpenAPI 0.14.0 生成类型；没有手写重复 DTO 或第二套 Mock Schema。
- `/my-learning` 先分页读取本人 `ACTIVE` 报名，再分别从 Course 读取已发布课程详情、从 Learning 读取课程进度。两侧使用 `Promise.allSettled` 独立组合，单门课程的部分失败不会抹掉已经成功的报名或另一侧事实。
- 课程详情仍由 Course 公共详情驱动；登录会话恢复且具备 `learning:access` 后才读取本人报名和进度。报名成功后使用 Learning 返回事实，再跳到既有学习页；Learning Outbox 到 Course 播放投影的最终一致窗口显示明确 403 重试说明。
- 学习页先恢复当前发布课程和路由课时，再读取报名、进度和本人笔记。PUBLIC 深链接可幂等加入“我的学习”；报名制试看不隐式报名，非试看仍由 Course/Media 服务端访问判断拒绝。
- 课程完成度按已完成课时/当前发布目录汇总，只在课程详情与 `/my-learning` 展示；单课学习页读取 video 实际时长，以当前位置计算当前视频百分比，不再把整课完成度或累计观看秒数混在课时面板中。
- 播放器按真实播放墙钟时间生成进度增量，默认每累计 15 秒、暂停或结束时发出领域中立样本。页面为每个样本只生成一个 `clientEventId`，串行上报；认证刷新与最多两次 429 退避复用完全相同的请求体，重复/乱序响应不会让本地进度倒退。
- 后台播放授权刷新先取得新清单，再复用现有 HLS/MediaSource 加载，初次加载以外不展示授权 loading，也不在请求开始时销毁当前播放管线。初始恢复位置只在首次装载/切换资源时读取；服务端进度回写不再驱动 `video.currentTime`，课时页位置显示降为秒级更新，减少无意义的整页重绘。
- 笔记支持本人列表、新建、按 revision 更新、删除和按时间点定位播放器。409 时保留编辑输入并提示重新载入；422 字段错误展示在输入区。
- 路由切换或离页会中止 Course/Learning/Media 请求、429 等待与未发送进度队列，清理 HLS 实例、授权刷新定时器和 video 临时地址。

## 使用的 API

本任务没有修改 `docs/api/openapi.yaml`，直接复用并重新生成/校验以下 OpenAPI 0.14.0 契约：

- `GET/POST /api/v1/learning/enrollments`
- `GET /api/v1/learning/courses/{courseId}/progress`
- `POST /api/v1/learning/courses/{courseId}/lessons/{lessonId}/progress-events`
- `GET/POST /api/v1/learning/courses/{courseId}/lessons/{lessonId}/notes`
- `PUT/DELETE /api/v1/learning/notes/{noteId}`
- 既有 Course 公开课程详情和 Media/Course 播放授权链路

统一页面错误适配覆盖 401、403、404、409、422、429、503 与浏览器离线 `TypeError`。429 只有三次总尝试，不形成无界重试；404 区分课程/课时不可见，403 区分能力、报名事实和报名投影最终一致窗口。

## 数据库、迁移与环境

生产代码没有创建或修改数据库、表、Flyway 迁移、事件契约或 Seed。真实验收只使用用途明确的既有 `edu_platform_iam_dev`、`edu_platform_course_dev`、`edu_platform_learning_dev`、`edu_platform_media_dev`，没有创建新 Schema 或业务表。

学生端继续使用既有 `VITE_GATEWAY_BASE_URL`。真实本地验收将页面/Gateway 统一为 `http://127.0.0.1:5173` / `http://127.0.0.1:8080`；日常默认的 `localhost:5173` / `localhost:8080` 同样有效，但两组主机名不得混用，否则既有 `SameSite=Strict` HttpOnly 刷新 Cookie 不能跨站恢复。Java 服务仍通过 `scripts/Invoke-WithLocalEnvironment.ps1` 注入本地 DPAPI 凭据，未读取、打印或提交密码、Token、AccessKey 或签名 URL。

启动方式：

```powershell
pnpm.cmd --dir web-apps --filter @edu/student-web run dev
```

Gateway、IAM、Course、Learning、Media 按仓库 `AGENTS.md` 中的 Maven 命令和 local profile 启动；启用真实报名投影验收时设置 `RABBITMQ_ENABLED=true`。

## 自动化测试与实际结果

已执行并通过：

- `pnpm.cmd --dir web-apps install --frozen-lockfile --offline`：锁文件无漂移，全部依赖由本地 store 复用；沙箱内链接阶段曾持续无输出，停止后使用同一命令在批准的沙箱外完成。
- `pnpm.cmd --dir web-apps run typecheck`：OpenAPI 客户端重新生成，3 个应用与 5 个共享包全部通过。
- `pnpm.cmd --dir web-apps run test`：23 个测试文件、140 项全部通过。新增覆盖 Gateway 路径、报名/进度/笔记、刷新会话失效映射、429 请求体复用、播放墙钟计时、暂停/结束上报、当前视频百分比、重复/乱序单调合并、离页中止、能力路由及 T12A/T12B/T15 边界。
- `pnpm.cmd --dir web-apps run build`：学生、教师、管理三端生产构建全部通过；只有既有 `hls.js` 约 523 kB chunk 警告，无构建失败。
- `pnpm.cmd --dir web-apps run check:api`：通过，生成客户端已跟踪且与 OpenAPI 0.14.0 无漂移。
- `git diff --check`：通过。
- 人工可读性检查：完成。新增页面、API 适配器、进度队列和共享播放器跟踪器均有中文 TSDoc/JSDoc，说明输入、输出、权限、有限重试、最终一致与离页副作用；生成代码未手工改注释。

## 真实浏览器验收与测试数据

验收链路使用实际 Vue 页面、Gateway、IAM、Course、Learning、RabbitMQ 报名投影、Media 短期授权与真实 HLS，不以 Mock 代替：

- 8080～8084 五个服务健康检查均为 `UP`，学生端 5173 可访问。
- 匿名课程详情展示真实已发布课程且没有 `<video>`；点击开始学习保留完整 `/learn/{courseId}/{lessonId}` 深链接进入统一登录。390×844 下无横向溢出，固定导航顺序和键盘焦点轮廓有效。
- 登录后打开课程 `5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057`、课时 `18d2d6cc-f374-46b1-ad81-743f169d0ce9`：页面幂等创建 ACTIVE 报名，播放器只有一个 video，真实 HLS `readyState=4`、时长 92.04 秒、非静音。
- 实际播放约 29 秒产生两条增量进度事件；首次 15 秒周期上报后，暂停再次同步，Learning 聚合为位置 28 秒、累计观看 29 秒。创建并编辑 `T12A 验收笔记 20260826`，revision 从 0 增为 1。
- 刷新相同 `/learn/...` 深链接后仍处于登录态，播放器重新达到 `readyState=4` 并恢复到 28 秒，笔记和进度均恢复。`/my-learning` 显示真实课程、0% 完成度、0/2 课时、最近位置 0:28 和“继续学习”；课程详情显示“继续学习”且 `<video>` 数仍为 0。
- `/my-learning` 与学习页在 390×844 视口的 `scrollWidth` 均等于 `clientWidth=375`，没有横向溢出；移动端仍只有一个 video、进度/笔记可见。固定导航高亮正确，原生按钮/链接/输入均可键盘操作并有 `:focus-visible` 轮廓。浏览器控制台 warning/error 为 0。
- 针对用户报告的短周期闪动，使用只读诊断从既有 `edu_platform_media_dev` 和 OSS 下载该课时稳定 HLS 派生产物到本机临时目录：清单为 9 个 10 秒切片加 1 个 2.04 秒尾片，完整 FFmpeg 解码退出码 0，逐帧 25fps 时间轴连续，源文件与 HLS 均无亮度突变/黑帧，单片 GET 为 50～427ms。真实浏览器多轮整段复播依次在约 10/35/58/83 秒、20/44/67 秒重复闪动；播放期间始终 `readyState=4`、未暂停、未 seek、未换 URL。关闭进度回写位置监听、ManagedMediaSource 和 HLS Worker 后闪动时间点不变，排除了授权 TTL（默认 10 分钟）、进度保存、Vue 重挂载、源视频画面、解码损坏和明显下载不足。问题已收敛到既有 MPEG-TS HLS 经 Chromium MSE 缓冲窗口切换的链路，fMP4 HLS v2 尚未实现，将在下一轮继续。临时诊断源码和下载文件均删除，未写数据库或 OSS。

本轮仅为 `student_test_01` 创建最小专用数据：上述课程的一条报名、一条聚合进度、两条进度事件和一条带 `T12A` 标识的笔记；播放链路另产生 5 条 Media 访问授权、5 个服务授权 nonce/精确临时 HLS 清单，并因登录/刷新产生 7 条刷新会话和 4 条登录审计。没有创建新账号、课程、课时、媒体资产、上传或长期 OSS 对象。

验收结束后，临时清理用例先按 actor/开始时间查询每个授权的精确 Object Key，经既有 `ObjectStorage.delete` 删除 5 个临时清单，再删除对应授权/nonce；随后按 learner/course/enrollment 和时间边界删除 Learning 报名、进度、事件、笔记、Outbox，Course 报名投影/消费记录，以及 IAM 本轮刷新会话/登录审计。最终残留审计对四个 dev Schema 的上述表逐项断言为 0。没有清空表、列举 Bucket、修改既有课程/媒体或影响其他测试数据；所有临时清理源码在提交前删除。Media 临时清理第一次启动遇到测试 classpath 中 Gateway 自动配置冲突，沿用仓库 T09A 真实验收的精确自动配置排除后复跑成功，失败尝试未执行删除。

## 人工演示步骤

1. 启动五个 Java 服务与学生端，打开 `/courses/5cfa6b44-92f4-4d11-9fcb-67dd1c8c5057`。
2. 匿名点击“开始学习”，确认登录地址保留 `/learn/...` 回跳；登录后进入同一课时。
3. 播放超过 15 秒后暂停，确认课时页只显示当前视频百分比，课程详情显示整课完成度；新增笔记、编辑并点击时间点定位。
4. 刷新深链接，确认 HttpOnly 刷新会话、播放位置和笔记恢复；离开页面后确认不再发出旧课时请求。
5. 进入 `/my-learning`，确认真实报名卡片、进度、最近位置和“继续学习”；再到课程详情确认行动按钮显示“继续学习”。
6. 使用同时具有 `learning:access` 与 `teaching:access` 的测试身份，确认工作台选择同时提供学习和教学空间；移除学习能力时学习路由进入 403。

## 已知风险与后续依赖

- Learning Outbox 到 Course 报名访问投影是最终一致；刚报名时播放可能短暂 403，页面提供有界、可解释的人工重试，不轮询放大压力。
- 既有 MPEG-TS HLS 在 Chromium MSE 缓冲窗口切换时仍会主观闪动，是本轮未关闭的验收风险。下一轮应实现版本化 fMP4 HLS 派生（init 与 m4s 组件授权、旧 v1 兼容和最小开发资产重处理），再用同一播放器整段复验；不得以继续叠加前端缓冲参数冒充修复。
- `hls.js` 生产 chunk 仍约 523 kB，是既有 T08B 动态播放依赖；本任务没有复制依赖或播放器。进一步拆分需以性能数据单独评审。
- `/my-learning` 收藏没有现行 OpenAPI 契约，未伪造入口或数据；需先由后续总控任务补契约。
- T12B、T12C、T15、T17 继续在冻结路由与稳定导航槽位内实现，不应复制学习页、播放器或 Learning DTO。

## Git 交付

- 分支：`codex/t12a-learning-progress-web`
- 实现/测试/验收提交：`dd491c4`
- PR：[#30 T12A 学习与进度前端](https://github.com/chengchen-ccnu/ccnu-edulion/pull/30)，Ready/Open；本任务不自行合并。
