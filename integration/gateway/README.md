# 能力网关 API

零依赖 Node 服务，只监听 `127.0.0.1`。启动：`node scripts/dev.mjs gateway`（或 `node integration/gateway/server.mjs`）。

## 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthz` | 三引擎状态 + 队列深度。Stirling 不可用时返回 503 |
| GET | `/api/engines` | 引擎明细（状态、版本、延迟、入口路径） |
| GET | `/api/capabilities` | 124 项能力，按分类分组，每项带 `available` / `reason` |
| POST | `/api/upload` | multipart 上传，返回落盘绝对路径（浏览器端用） |
| POST | `/api/run` | 提交任务，返回 202 + jobId |
| GET | `/api/jobs` | 最近 30 个任务 |
| GET | `/api/jobs/:id` | 任务详情：状态、日志、产物下载地址 |
| GET | `/api/jobs/:id/files/:name` | 下载产物 |
| POST | `/api/jobs/:id/cancel` | 取消排队中的任务（已开始的无法取消） |

## 典型调用

```bash
# 看能力
curl -s http://127.0.0.1:8931/api/capabilities | jq '.stats, .engines'

# 上传（浏览器场景）
curl -s -F "file=@a.pdf" -F "file=@b.pdf" http://127.0.0.1:8931/api/upload
# {"dir":"...","files":[{"name":"a.pdf","path":"C:\\...\\a.pdf","bytes":1234}, ...]}

# 提交合并（桌面场景，直接给路径）
curl -s -X POST http://127.0.0.1:8931/api/run \
  -H 'content-type: application/json' \
  -d '{"capabilityId":"stirling.merge.merge-pdfs","files":["C:\\tmp\\a.pdf","C:\\tmp\\b.pdf"],"options":{"sortType":"orderProvided"}}'
# {"jobId":"...","status":"queued","poll":"/api/jobs/..."}

# 轮询
curl -s http://127.0.0.1:8931/api/jobs/<jobId>
```

## 状态机

```
queued ──▶ running ──▶ done
   │           │
   └─ cancel ──┴──────▶ failed
```

- 并发上限默认 2，超出排队
- 产物落在 `.work/jobs/<uuid>/`，只保留最近 200 个作业
- 每个任务最多保留 200 行日志，接口只返回最后 30 行

## 错误约定

| HTTP | 场景 |
|---|---|
| 400 | 文件不存在 / files 为空 / 非法 JSON / 请求体超限 |
| 403 | Origin 不在白名单（只允许 localhost、127.0.0.1、file://） |
| 404 | 未知 capabilityId / 任务不存在 / 产物已被清理 |
| 409 | 能力当前不可用（引擎 down）、对运行中任务调用 cancel |
| 413 | 上传超过 `PDF_MAX_UPLOAD` |
| 415 | `/api/upload` 不是 multipart |
| 500 | 适配器抛错（Stirling 返回非 2xx、CLI 退出码非 0 等） |

错误体：`{"error":"...","hint":"..."}`，`hint` 只在有明确下一步时出现。

## 安全边界

- 只监听回环地址，不接受外部请求
- `/api/run` 只接受本机可读文件路径；不提供任意路径读取接口
- 下载接口把文件名限制在作业目录内（`startsWith(dir + sep)` 校验），防目录穿越
- 文件名去掉 `\ / : * ? " < > |`，避免 Windows 路径注入
- CORS 白名单校验，恶意网页无法借浏览器调用本机处理能力
