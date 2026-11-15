# 桌面端接入

`pdf-hub/` 是一个无构建依赖的单页工作台，直接消费网关 API。两种用法：

## 方式一：直接打开（已验证可用）

```bash
node scripts/dev.mjs gateway
# 双击 integration/desktop/pdf-hub/index.html
```

网关对 `file://`（Origin: null）放行，所以双击即可，不需要另起静态服务器。

端口不是 8931 时：

```
file:///.../pdf-hub/index.html?gateway=http://127.0.0.1:18931
```

## 方式二：挂进 ToolKnit Desktop

ToolKnit 的工具是「懒加载插件」，一个工具 = 一个目录 + 一条注册项。改动只有两处：

### 2.1 复制页面

```powershell
Copy-Item -Recurse integration\desktop\pdf-hub `
  engines\toolknit-desktop\toolknit-desktop\src\features\pdf-hub
```

### 2.2 注册到工具表

编辑 `engines/toolknit-desktop/toolknit-desktop/src/features/lazy-tools.js`，在 `LAZY_TOOL_SPECS` 里加：

```js
'pdf-hub': Object.freeze({
  overlayId: 'pdfHubOverlay',
  markup: () => import('./pdf-hub/template.html?raw'),
  load: () => import('./pdf-hub/tool.js'),
  init: 'initPdfHubTool'
}),
```

并在 `index.html` 对应位置加一个入口节点（参考 `pdf-merge` 的卡片写法）：

```html
<div class="pdf-merge-overlay pdf-merge-v2" id="pdfHubOverlay" aria-hidden="true"></div>
```

### 2.3 为什么要包一层

直接 `import` 我们的 `app.js` 会与 ToolKnit 的 `app-composition.js`（工具初始化协议）冲突：
它期望每个工具导出 `initXxxTool({ overlay, isTauri, getOutputDir, notify, ... })` 并在其
生命周期内自管理 DOM。因此接入时需要：

- `tool.js` 里 `export function initPdfHubTool(deps)`：拿到 `overlay`，把 `index.html` 内容注入，
  再把 `deps.getOutputDir()` 提供的输出目录作为下载落点（而不是浏览器 download）
- `app.js` 内的 `API` 常量改成从 `deps.gatewayUrl ?? 'http://127.0.0.1:8931'` 取
- 下载分支改为 `deps.isTauri ? 调用 Tauri 保存对话框 : 浏览器 download`

这三处是主要工作量；如果只是想用，方式一已经足够。

## Tauri 侧：让桌面壳托管网关

桌面应用不该要求用户手动起服务。在 `src-tauri/src/` 加一个启动器：

```rust
use std::process::{Child, Command};
use std::sync::Mutex;

pub struct Gateway(pub Mutex<Option<Child>>);

#[tauri::command]
fn start_gateway(state: tauri::State<Gateway>, app: tauri::AppHandle) -> Result<u16, String> {
    let mut guard = state.0.lock().map_err(|e| e.to_string())?;
    if let Some(child) = guard.as_ref() {
        if child.id() != 0 { return Ok(8931); }
    }
    let root = app.path().resource_dir().map_err(|e| e.to_string())?;
    let child = Command::new("node")
        .arg(root.join("gateway/server.mjs"))
        .env("PDF_GATEWAY_PORT", "8931")
        .spawn()
        .map_err(|e| format!("启动网关失败: {e}"))?;
    *guard = Some(child);
    Ok(8931)
}

#[tauri::command]
fn stop_gateway(state: tauri::State<Gateway>) {
    if let Ok(mut guard) = state.0.lock() {
        if let Some(mut child) = guard.take() { let _ = child.kill(); }
    }
}
```

打包时把 `integration/gateway` 与 `integration/registry` 放进 `tauri.conf.json` 的 `bundle.resources`，
并把 Node 运行时作为 sidecar（或用 `pkg`/`node --experimental-sea` 打成单文件）——Tauri 不带 Node，
用户机器上没有 node 时网关起不来，这是接入前必须决定的一件事。

## 已知限制

- 浏览器拿不到本地绝对路径，所以页面走 `POST /api/upload` 上传；桌面端可改用路径直传，省一次复制
- `native` 类能力在浏览器里只能提示「到桌面端执行」，因为它们是 ToolKnit 的前端页面
- 工作台不做文件预览，PDF 渲染需要 pdf.js，属于下一阶段
