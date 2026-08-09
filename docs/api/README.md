# API contracts

`openapi.yaml` 是浏览器经 Gateway 访问平台 REST API 的唯一字段来源，文件中的 `info.version` 是当前契约版本事实。现有契约覆盖登录/当前用户、课程分类与发布目录、教师课程编排、媒体上传/查询/访问，以及课程域学生播放和教师预览授权；后续报名、测评、AI、运营接口只能通过向前版本化扩展加入。教师和媒体写接口只通过 Gateway 暴露，并依赖 Gateway 重建的可信身份，浏览器不得直连领域服务或内部接口。

常用命令：

```powershell
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run check:api
pnpm.cmd --dir web-apps run mock:api
```

Mock 地址为 `http://127.0.0.1:4010`。前端不得维护独立 Mock DTO；生成文件 `web-apps/packages/api-client/src/generated/schema.d.ts` 由本目录契约产生。
