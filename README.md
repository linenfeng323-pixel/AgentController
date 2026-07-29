# AI 操控盒子（AgentController）

> 在搜索栏输入任意 AI 名称 / 网址（豆包、DeepSeek、ChatGPT …），登录账号后即可让 AI 操控手机实现各种操作。
> Root 系统级 + 无障碍兜底混合执行，代理循环自动观察-思考-行动，PC/云端可协同。

## 功能一览

- **任意 AI 一键接入**：豆包 / DeepSeek / Kimi / 通义 / 文心 / 智谱 / 元宝 / ChatGPT / Claude / Gemini / Coze / 星火，或任意自定义网址
- **账号登录自动记忆**：复用 Cookie / localStorage，下次免登
- **Root 系统级操控**（`su`）：点击 / 滑动 / 输入 / 按键 / 启停 App / 截图 / 系统设置 / 任意 shell
- **无障碍兜底**：按文字/ID 找节点点击、中文输入、手势
- **自然语言下指令**：AI 自动拆解为 JSON 操作步骤并执行
- **代理循环**：观察-思考-行动，自适应降频防漏（借鉴白给 v19 防漏人思路）
- **任务缓存引擎**：100ms 刷新 / 1.5s 保留 / 5s 最大过期 / 队列超时保护
- **录制回放 / 命令历史**：手动操作录制成脚本一键回放，AI 执行批次留档
- **设备诊断**：root / 无障碍 / 屏幕 / 性能 / 关键包 / tombstone 崩溃
- **崩溃捕获 + 地址符号化**：Java 崩溃落盘，native 地址在 /proc/maps 反查
- **PC/云端协同**：WebSocket 长连接，会话粘连，自动重连
- **GitHub Actions 自动修复**：编译失败时代理自动分析日志、改代码、推送、触发新工作流，直到成功

## 界面

Mac 风格左侧 Dock：首页 / AI / 录制 / 诊断 / 设置 / 日志。深色主题，圆角卡片。

## 环境依赖

### 编译

- JDK 17
- Android SDK 34（compileSdk 34，minSdk 24）
- Gradle 8.5（已内置 wrapper 配置）
- AndroidX（appcompat / material / constraintlayout / recyclerview / gridlayout / coroutines）

### 运行

- Android 7.0+ 设备
- **Root 权限**（Magisk 等）：启用系统级操控；无 root 也能用无障碍模式
- 无障碍服务：在系统设置里开启“AI 操控盒子”
- 悬浮窗权限（可选）

### PC/云端协同（可选）

- Python 3.8+
- `pip install requests`
- 运行 `python3 scripts/pc_bridge_server.py --port 9912`
- App 设置页填 `ws://<PC IP>:9912` 并开启桥接

## 编译

```bash
cd AgentController
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

CI 推送代码即可触发 `.github/workflows/android-build.yml` 自动编译，产物作为 Artifact 上传。

## 自动修复代理

编译失败时，工作流会调用 `scripts/auto_fix_agent.py`：

1. 读取失败日志
2. 规则匹配常见错误（未声明组件、缺依赖、未 import、缺资源）
3. 可选调用大模型（配置 `OPENAI_API_KEY`）
4. 提交并推送补丁
5. 触发新工作流并监控，失败则回到步骤 1，最多 8 次

独立监控工作流：

```bash
python3 scripts/workflow_monitor.py --repo owner/name --token ghp_xxx --watch
```

## 安全说明

- Root 与无障碍权限强大，请仅在自己的设备上使用
- 所有指令执行均有日志可查，可随时中断
- PC 桥接建议在内网使用，公网请套 TLS（`--cert/--key`）

## 致谢

防漏人缓存、自适应降频、SHA-256 完整性校验等思路借鉴自白给 v19 项目的工程实践。
