# 江苏大学学伴 2.0.1

面向江苏大学的个人 Android 课表与考试工具，非学校官方应用。与原来的 `cn.campusai.campus_ai` 应用使用不同包名，可以共存；本项目参考旧版的教学周追踪规则，不包含旧版聊天、社区等功能。

## 安装与第一次使用

从 [Releases](https://github.com/YXU114514/ujs-study-companion/releases/latest) 下载 `UJS-Companion-2.0.1.apk`，支持 Android 8.0 及以上。本地构建的安装包位于 `artifacts/`，不会作为源码文件提交。本项目发布包可直接覆盖相同签名的 2.0.0 版本，保留本机数据。首次打开会进入学校官方 HTTPS WebVPN 登录页面，在网页里完成账号、密码与验证码验证。登录后依次打开个人课表和考试查询页面，取得数据后返回应用。

如果学校页已登录但停留在页面上，可点右上角“读取”。设备需要支持文档开始注入的 Android System WebView；版本过旧时应用会明确提示更新。

请允许通知权限，才能收到课程变动、新考试或重新登录提醒。拒绝通知权限不影响打开应用查看和手动刷新。

## 功能

- 底部切换条：左侧课表、右侧考试。
- 课表区域向左滑动看下一周，向右滑动看上一周；也有前后箭头和“本周”按钮。
- 点击课程卡片查看教师、教室、节次和周次；实践安排单独展示。无法识别的时间文本保留供核对。
- 课表目标更新周期为 12 小时，考试为 3 小时，联网后由 Android WorkManager 执行；退出界面后仍可调度。
- 首次获取建立基线，之后按课程和考试业务内容比较变化。响应顺序和抓取时间变化不会触发提醒。
- 考试分页必须完整取得后才替换缓存；请求失败、格式异常或登录失效都保留上次的数据。
- 离线可以查看最近一次成功获取的日程。

后台任务受 Android 省电、休眠和厂商后台策略影响，12/3 小时不是准点保证。强制停止应用后，需要重新打开才能恢复调度。可从应用“通知与后台运行设置”进入系统应用设置。

## 登录失效

后台先以手机已有的会话访问正常业务页面，允许学校已有统一认证会话通过正常跳转续登。如果学校再次要求密码、图片验证码或其他人工确认，会显示重新登录通知；点击通知在学校页面验证后继续同步。应用不自动识别验证码，也不会保存或填写学校密码。

本版本没有绑定远程服务器或推送平台，提醒是设备本地通知。

## 本周第几周

沿用原源码 `semester_clock.dart` 的规则：按北京时间日期计算，以学期第一教学周的周一为锚点，`floor((今天 - 首周周一) / 7) + 1`。手机在其他时区时仍以北京时间为准。

从旧源码沿用的 **2026-08-31** 校准只适用于 **2026 学年、学校学期代码 3**，不作为其他学期通用校历。新学期缺少锚点时提示校准，不把第 1 周冒充本周。可在右上角设置“今天第几周”或“第一教学周周一”；每个学期的校准独立保存，数据更新不会覆盖它。

前台检查北京时间日期变化，恢复到前台时重新计算；跨日期自动回到本周。学期总周数可设置 1—60，未设置时采用旧版 60 周上限。

## 数据与权限

- 手机自己的登录状态仅用于手机自身同步。APK 不含电脑登录 Cookie、学号或真实课表。
- WebView 使用 Android 的应用私有 Cookie 存储；应用额外保存的查询模板及 Cookie 快照使用 AndroidKeyStore AES/GCM 加密。
- 课表和考试缓存位于应用私有目录，系统备份已禁用。展示数据不保留原始响应里的学生姓名、学号、`userModel` 等字段。
- 只捕获已确认的两个只读查询接口，不捕获登录表单。网页消息限定学校 HTTPS origin、实际业务页和主框架；证书错误不会放行。
- “清除本机数据”会结束本应用同步任务，再删除缓存、模板和 WebView 会话，不影响旧版应用。

## 构建

Java 17，Android compileSdk 36 / targetSdk 35 / minSdk 26，AGP 8.12.2。

准备 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0，并设置 `JAVA_HOME` 和 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）。项目包含 Gradle 8.14.3 Wrapper。Windows PowerShell 7 中执行：

```powershell
.\build.ps1
```

也可用 Android Studio 打开项目，或在 macOS/Linux 中运行：

```sh
./gradlew assembleDebug testDebugUnitTest
```

生成最终签名 release 安装包：

```powershell
.\sign-release.ps1
```

`build.ps1` 参数可指定 `JavaRoot`、`AndroidSdk` 和 `GradleCommand`，不修改系统全局环境。可选的 `.build.local.json` 仅保存本机路径，已被 Git 忽略。签名密钥位于本项目 `.signing/`，与密码文件一起留在本机用于后续覆盖更新，请保留；不能提交到仓库或打进安装包。仓库不包含发布者密钥。`sign-release.ps1` 会复用本机密钥，最终 APK 关闭了调试标志。自行生成的新密钥不能覆盖安装其他签名的发布包。

额外执行检查：

```powershell
.\build.ps1 -Tasks assembleDebug,testDebugUnitTest,lintDebug
node .\tools\test-capture.cjs
```

Android 测试覆盖北京时间跨周、学期校准、单双周、完整分页、变化比较、通知去重、底部切换、左右滑动和无数据界面。JavaScript 测试使用虚构响应验证查询白名单、登录表单排除、请求头过滤、XHR/fetch 与重复注入。

当前版本通过编译和 24 项自动化测试。实际学校账号在 Android WebView 中的首次登录，以及手机厂商对后台任务的限制，仍需安装后确认。开发阶段在桌面端验证过学校会话恢复和两个真实查询接口；这不等于 Android 真机验证。

## 主要文件

- `MainActivity.java`：课表、考试、周次浏览和设置。
- `LoginActivity.java` 与 `assets/capture.js`：学校登录、限定的业务响应捕获。
- `SyncEngine.java`：复用真实查询模板、会话续登及分页读取。
- `SyncScheduler.java` / `SyncWorker.java` / `Notifier.java`：周期更新与本地通知。
- `AppStore.java` / `SemesterClock.java` / `WeekPattern.java`：缓存、变化比较、凭据存储和教学周算法。
