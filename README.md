# Oplus Assistant Hook

基于 LSPosed / libxposed API 101 的 Xposed 模块，用于在 ColorOS 上拦截部分系统助手入口，并将其替换为 Google Gemini 或 Google Circle to Search（一圈即搜）。

![Demo GIF](assets/demo.gif)

## 功能

- 电源键长按替换：拦截 ColorOS 长按电源键唤醒小布助手的行为。
- 手势指示条长按替换：拦截长按导航手势指示条唤醒小布识屏的行为。
- Contextual Search 适配：为不原生支持 Circle to Search 的 ColorOS 设备补齐相关系统服务路径。
- Google App 设备伪装：仅在 Google 搜索应用进程内伪装为 Samsung S24 Ultra，以解锁 Circle to Search 可用性。
- Material 3 设置界面：提供模块状态、默认数字助理检查和功能开关。

## 支持范围

| 项目 | 要求 |
| --- | --- |
| 系统 | ColorOS 16 或相近 Android 版本 |
| 设备 | OnePlus / OPPO / realme |
| 框架 | 支持 libxposed API 101 的 LSPosed/现代 Xposed 框架 |
| 必需应用 | Google 搜索应用（`com.google.android.googlequicksearchbox`） |
| Root | Magisk / KernelSU / APatch |

## 安装

1. 确保设备已 Root，并已安装 LSPosed。
2. 安装 Google 搜索应用。
3. 安装本模块 APK。
4. 在 LSPosed 中启用模块，作用域包含：
   - `system`
   - `com.android.systemui`
   - `com.google.android.googlequicksearchbox`
5. 重启系统。
6. 打开模块应用，按需启用电源键或手势指示条替换。
7. 建议将系统默认数字助理设置为 Google。

## 构建

环境要求：

- JDK 17
- Android SDK 35
- Android Gradle Plugin 8.7.3
- Gradle 8.9（已包含 wrapper 配置）
- libxposed API 101.0.1（`compileOnly`，运行时由框架提供）

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

在 Windows PowerShell 中：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

如果 Gradle 提示找不到 Android SDK，请创建本地 `local.properties`：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

`local.properties` 不应提交到版本库。

## 模块结构

| 文件 | 进程 | 说明 |
| --- | --- | --- |
| `ButtonInterceptorHooker` | `system_server` | 拦截电源键长按助手入口 |
| `GestureBarHooker` | `com.android.systemui` | 拦截手势指示条长按入口 |
| `ContextualSearchHooker` | `system_server` | 适配 Contextual Search 服务，并仅对可信调用方放宽权限路径 |
| `VimsHooker` | `system_server` | 管理 VoiceInteractionManagerService 触发期间的临时资源状态 |
| `ResourcesHooker` | `system_server` | 临时替换 Contextual Search 相关系统资源字符串 |
| `DeviceSpoofHooker` | Google App | 在 Google 搜索应用内伪装设备型号 |
| `AppBlockerHooker` | `system_server` | 按配置阻止原小布助手服务启动 |
| `SystemContextHooker` | `system_server` | 捕获系统 Context 供其他 hook 使用 |

## 安全说明

本模块会 hook `system_server` 和 SystemUI，并涉及 binder 调用身份与系统服务权限路径。修改相关逻辑时应遵守两条原则：

- 默认关闭：偏好读取失败时不启用替换行为。
- 最小放行：仅对模块自身触发路径放宽权限检查，避免全局绕过系统权限。
- 现代入口：模块入口、作用域和元数据位于 `app/src/main/resources/META-INF/xposed/`。

## 许可证

当前仓库尚未包含正式 `LICENSE` 文件。公开发布前请补充明确的开源许可证。
