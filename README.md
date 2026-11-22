# LTW Auto Project

基于 **LSPosed (Xposed API >= 93)** + **KernelSU** 辅助模块的 **Link to Windows（连接至 Windows）** 增强工具，主要面向 **OPPO ColorOS / Android 15~17**（实机验证于 **ColorOS 17 / Android 17**，`minSdk = 33`）。

## 核心功能（v1.7.0）

1. **免确认自动授权录屏与跨屏权限**
   - 自动通过系统 `MediaProjection` 录屏确认弹窗及微软设备集成服务（DIS）授权提示，锁屏状态下无需唤醒手机手动点击。
   - 配合 KernelSU 辅助模块在每次开机后持久化授予 `PROJECT_MEDIA`、`TURN_SCREEN_ON`、`RUN_ANY_IN_BACKGROUND`、`WAKE_LOCK` 权限并加入电池优化白名单。
2. **锁屏 / 息屏下独立应用投屏（App Streaming）**
   - 为 `MS_VIRTUAL_DISPLAY*` 虚拟屏配置独立 `DisplayGroup`、`ALWAYS_UNLOCKED`、`NEVER_BLANK` 及虚拟触控支持，并在锁屏期间维持 `STATE_ON` 与输入可交互。
   - 拦截 App 层与系统层的 `wakeUp` / 锁屏检测 / 高等级 WakeLock / 键盘键码误唤醒，使电脑端投屏运行独立应用时手机物理屏幕保持熄灭（仍保留电源键、音量键等实体键正常亮屏）。
3. **息屏下整机投屏（Phone Screen / `AUTO_MIRROR`）**
   - 息屏状态下发起整机镜像时，逻辑唤醒主屏并保持 `VirtualDisplay` 渲染流正常输出（解决编码卡在“仍在处理…”），同时在驱动代理层强制物理屏 OLED 维持 `DISPLAY_POWER_MODE_OFF`（息屏可见电脑画面）。
   - 按电源键或指纹解锁会立即恢复物理屏正常亮度；电脑端断开投屏后自动将主屏放回休眠。
4. **绕过 ColorOS 投屏隐私保护黑屏**
   - 豁免 ColorOS `ScreenProtectionController`、`ContentRecoderExtImpl` 及 AOSP `SensitiveContentProtectionManagerService`，并在 `DisplayContentExtImpl` 允许镜像根节点忽略安全标志，使锁屏界面、PIN/密码盘与安全键盘在电脑端正常显示。
5. **性能与抗混淆设计**
   - 预缓存与懒缓存高频反射 `Field`/`Method`、`DisplayThread` Handler，一次性探测 Hook 在获取目标后自动 `unhook` 卸载。
   - `PhoneWindowManager#wakeUpFromWakeKey` 早退前置过滤；生产构建默认关闭高频日志输出（`VERBOSE_LOGS = false`）。
   - DIS 模块通过未混淆的 `Activity` 锚点及方法/字段签名动态解析混淆类与字段，降低后续版本更新失效风险。

## 模块结构

- `app/`：LSPosed 模块源码（Android Gradle 工程）
  - [`HookEntry.java`](app/src/main/java/com/ltw/autoproject/HookEntry.java)：分发 `android`、`com.android.systemui`、`com.microsoft.appmanager`、`com.microsoft.deviceintegrationservice`
  - [`SystemServerHooks.java`](app/src/main/java/com/ltw/autoproject/SystemServerHooks.java)：`system_server` 权限、虚拟屏、PowerGroup、按键防唤醒、息屏镜像与投屏隐私豁免
  - [`SystemUiHooks.java`](app/src/main/java/com/ltw/autoproject/SystemUiHooks.java)：SystemUI 录屏授权与 ColorOS 禁止录屏检查豁免
  - [`AppManagerHooks.java`](app/src/main/java/com/ltw/autoproject/AppManagerHooks.java)：`com.microsoft.appmanager` 锁屏/亮屏状态与 WakeLock 拦截
  - [`DeviceIntegrationHooks.java`](app/src/main/java/com/ltw/autoproject/DeviceIntegrationHooks.java)：`com.microsoft.deviceintegrationservice` 动态签名解析与静默授权
  - [`HookUtils.java`](app/src/main/java/com/ltw/autoproject/HookUtils.java)：反射缓存、安全挂载与分级日志工具
- `ksu-module/`：KernelSU 辅助模块（开机 `service.sh` 持久化 AppOps 与 Doze 白名单）

## 安装与使用

1. 前往 GitHub **Releases** 下载 `ltw-autoproject.apk` 与 `ltw-autoproject-ksu.zip`。
2. 安装 `ltw-autoproject.apk`，在 **LSPosed 管理器** 中启用模块，并确认勾选作用域：
   - 系统框架（`android`）
   - 系统界面（`com.android.systemui`）
   - 连接至 Windows（`com.microsoft.appmanager`）
   - 设备集成服务（`com.microsoft.deviceintegrationservice`，若机型存在）
3. 在 **KernelSU 管理器** 中刷入 `ltw-autoproject-ksu.zip`。
4. **重启手机** 使系统级 Hook 与开机权限规则生效。

## 兼容性说明

- **ColorOS / OxygenOS / realme UI（Android 15~17 同代底座）**：Oplus 专属类高度同源，完整功能可直接使用。
- **其他 Android 13+ 定制系统 / 类原生 AOSP**：不存在的厂商类会安全跳过不会导致卡死或崩溃；AppOps、微软客户端锁屏绕过及标准 AOSP 逻辑通常可用，但各厂商定制的投屏隐私黑屏保护及不同 Android 大版本间的框架签名差异未做专门适配。

## 本地编译

在项目根目录创建 `local.properties` 并指定本机 Android SDK 路径：

```properties
sdk.dir=<你的 Android SDK 路径>
```

使用 JDK 17 执行 Release 构建：

```powershell
$env:JAVA_HOME = "<你的 JDK 17 安装路径>"
.\gradlew.bat :app:assembleRelease
```

## License

[MIT License](LICENSE)
