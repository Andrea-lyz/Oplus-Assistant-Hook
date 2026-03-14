# Oplus Assistant Hook

> 拦截 ColorOS 系统助手调用，替换为 Google Gemini 或一圈即搜（Circle to Search）

一个基于 LSPosed 框架的 Xposed 模块，适用于 **ColorOS 16**（一加 / OPPO / realme），可将系统默认的小布助手替换为 Google Gemini 或 Google 一圈即搜。

![Demo GIF](assets/demo.gif)

## ✨ 功能特性

### 🔘 电源键长按替换

拦截 ColorOS 长按电源键唤醒小布助手的行为，支持替换为：

- **Google Gemini** — 通过 VoiceInteractionManagerService 调用，体验与原生 Pixel 一致
- **Google 一圈即搜** — 通过 ContextualSearchManager 直接调用
- **不替换** — 保持原始小布助手行为

### 📱 手势指示条长按替换

拦截长按导航手势指示条唤醒小布识屏的行为，替换为 Google 一圈即搜。

> 需要在系统设置 > 系统导航方式中启用「长按手势指示条唤醒小布识屏」选项。

### 🔧 其他

- 自动伪装设备型号为 Samsung S24 Ultra（仅对 Google 搜索应用生效），解锁圈搜功能的设备限制
- 自动处理 ContextualSearch 服务注册与权限绕过
- 阻止原始小布助手服务启动，避免冲突
- 提供 Material 3 风格的设置界面，修改设置重启后生效

---

## 📋 系统要求

| 项目 | 要求 |
|------|------|
| **系统** | ColorOS 16（基于 Android 16） |
| **设备** | 一加 / OPPO / realme |
| **框架** | LSPosed（Zygisk 模式） |
| **必装应用** | Google 搜索应用（Google）|
| **Root** | 需要 Magisk / KernelSU / APatch |

---

## 📥 安装步骤

1. 确保设备已 Root 并安装了 LSPosed 框架
2. 安装 Google 搜索应用（`com.google.android.googlequicksearchbox`）
3. 下载本模块 APK 并安装  
4. 在 LSPosed 中启用本模块（作用域会自动配置）
5. **重启系统**
6. 打开模块应用，按需配置电源键和手势条的替换行为
7. 前往系统设置，将**默认数字助理**设置为 Google，或者长按电源键触数字助理选择页面（强烈建议）
8. 无需再次**重启系统**，设置即时生效

---

## 🏗️ 技术架构

模块通过 Hook 以下系统进程实现功能替换：

```
┌─────────────────────────────────────────────────────────┐
│                    LSPosed Module                        │
├───────────────┬──────────────────┬───────────────────────┤
│  system_server│    SystemUI      │     Google App        │
│  (android)    │                  │                       │
├───────────────┼──────────────────┼───────────────────────┤
│ • ButtonInter │ • GestureBar     │ • DeviceSpoof         │
│   ceptor      │   Hooker         │   Hooker              │
│   Hooker      │   Hook           │   (伪装为 S24 Ultra)  │
│               │   onLongPressed  │                       │
│ • Contextual  │                  │                       │
│   Search      │                  │                       │
│   Hooker      │                  │                       │
│               │                  │                       │
│ • VimsHooker  │                  │                       │
│               │                  │                       │
│ • Resources   │                  │                       │
│   Hooker      │                  │                       │
│               │                  │                       │
│ • AppBlocker  │                  │                       │
│   Hooker      │                  │                       │
│               │                  │                       │
│ • SystemCtx   │                  │                       │
│   Hooker      │                  │                       │
└───────────────┴──────────────────┴───────────────────────┘
```

### 核心 Hook 说明

| Hooker | 进程 | 功能 |
|--------|------|------|
| `ButtonInterceptorHooker` | system_server | 拦截电源键长按，替换为 Gemini 或圈搜 |
| `GestureBarHooker` | SystemUI | 拦截手势条长按，替换为圈搜 |
| `ContextualSearchHooker` | system_server | 注册 ContextualSearch 服务、绕过权限检查、清除跨进程调用身份 |
| `VimsHooker` | system_server | 管理 VoiceInteractionManagerService 会话的资源欺骗 |
| `ResourcesHooker` | system_server | 动态替换圈搜相关的系统资源字符串 |
| `DeviceSpoofHooker` | Google App | 伪装设备型号以解锁圈搜功能 |
| `AppBlockerHooker` | system_server | 阻止小布助手相关服务启动 |
| `SystemContextHooker` | system_server | 捕获系统级 Context 供其他 Hooker 使用 |

---

## 🔨 构建

### 环境要求

- Android Studio Ladybug (2024.2.1) 或更新版本
- JDK 17
- Android SDK 35

### 构建步骤

```bash
# Clone 仓库
git clone https://github.com/Andrea-lyz/Oplus-Assistant-Hook.git
cd Oplus-Assistant-Hook

# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本（需要配置签名）
./gradlew assembleRelease
```

构建产物位于 `app/build/outputs/apk/` 目录下。

---

## ⚠️ 注意事项

- 建议将系统默认数字助理设置为 Google，以确保 Gemini 功能正常
- 如遇到手势条长按无响应，请检查系统设置中是否已启用「长按手势指示条唤醒小布识屏」
- 本模块仅适用于 ColorOS 16，不支持 ColorOS 15 或其他 Android 系统

---

## 📜 开源协议

本项目基于 [GPL-3.0](LICENSE) 协议开源。

---

## 🙏 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) — Xposed 框架
- [Xposed API](https://api.xposed.info/) — Hook API
- Google — Gemini & Circle to Search
