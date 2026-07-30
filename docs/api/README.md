# API contracts

`openapi.yaml` 是浏览器经 Gateway 访问平台 REST API 的唯一字段来源。当前 0.3.0 版本冻结登录、当前用户、学生课程列表/详情、教师课程草稿/目录/发布和统一错误响应。课程写接口只通过 Gateway 暴露，并依赖 Gateway 注入的可信教师身份头。

常用命令：

```powershell
pnpm.cmd --dir web-apps run generate:api
pnpm.cmd --dir web-apps run mock:api
```

Mock 地址为 `http://127.0.0.1:4010`。前端不得维护独立 Mock DTO；生成文件 `web-apps/packages/api-client/src/generated/schema.d.ts` 由本目录契约产生。
