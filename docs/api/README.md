# API contracts

`openapi.yaml` 是浏览器经 Gateway 访问平台 REST API 的唯一字段来源。当前版本只冻结登录、当前用户、课程列表、课程详情和统一错误响应，不代表这些业务已经实现。

常用命令：

```powershell
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run mock:api
```

Mock 地址为 `http://127.0.0.1:4010`。前端不得维护独立 Mock DTO；生成文件 `web-apps/packages/api-client/src/generated/schema.d.ts` 由本目录契约产生。
