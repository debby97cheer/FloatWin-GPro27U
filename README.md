# FloatWin-GPro27U

> 让 Android TV 上的**任意 App 强制变成画中画悬浮窗**，浮在 HDMI 输入画面之上，可实时拖动、自由缩放、用遥控器操作。
>
> 把电视当 PC 显示器用时，一边看电脑，一边在屏幕上挂一个 Android 小窗。

开发于 **红米 G Pro 27U**（`XMI 27B3`，MT5877 / Android 14），原理基于 AOSP 标准 PiP 框架 + TV 私有 BSP 适配，其它 Android TV 机型可按 [移植指南](#移植到其它机型) 适配。

---

## 它解决什么问题

电视当显示器接 PC 时，系统里那套 PiP 基本不可用：

| 原生问题 | 本项目 |
|---|---|
| 只有少数 App 声明支持 PiP | 强制放行，**任意 App** 都能进 |
| 电视 ROM 的 PiP 固定右上角、尺寸写死 | 位置 / 尺寸实时可调，拖动即时跟手 |
| 缩放会触发 Activity 重建 → 闪黑底 | 走 TV 摆放接口，实时缩放无重建 |
| 悬浮窗抢走前台 → HDMI 黑屏重同步 | 自动把 HDMI 直通源挪回前台，不重握手 |
| 遥控器按键进不了悬浮窗 | 导航键中转进 App |
| 启动瞬间先全屏再缩小 | 启动期隐形压制，直到外框真的就位 |

**典型用法**：电视 HDMI 接 Windows，同时挂 localsend 收文件 / 音乐播放器 / 直播 / 监控 / 下载器。

---

## 功能特性

**TV 端（LSPosed 模块）**
- 强制任意 App 进入 PiP，浮在包括 HDMI 直通画面在内的所有界面之上
- **实时拖动**：改坐标文件即刻生效，无需重启 App
- **自由缩放**：任意宽高，与拖动走同一条实时摆放链路
- **遥控器中转**：方向键 / 确认键转发进悬浮窗里的 App（可开关）
- **零黑屏启动**：启动期隐形，避免"先全屏再缩放"
- **直通源前台保持**：PiP 存在期间把 HDMI 直通源顶回前台，Windows 不黑屏
- **竖屏手机预设**：让手机 App 用小屏布局（9:16 + 411dp + sw411）
- **返回键守护**（可选）：按遥控器返回 = `am force-stop`，小窗直接消失

**PC 端（图形控制台）**
- 可视化调位置 / 尺寸 / 预设 / density，不用敲 adb
- 一键「零黑屏启动」、应用列表双击即启动、**强制停止**
- 实时日志、注入状态检测、SELinux 修复、应急恢复
- 深浅色主题、全页面滚动

---

## 快速开始

### 前置条件

- 电视已 **root** 并装有 **LSPosed**（LSPatch 亦可，作用域需包含系统框架）
- 电视开启 **ADB 调试**（无线 `adb tcpip 5555` 或 USB）
- PC 装有 adb（控制台会自动找；找不到可在界面里浏览指定）

### 安装

1. 装 TV 端 APK：
   ```bash
   adb install -r releases/FloatWin-v34.apk
   ```
2. 打开 **LSPosed → 模块**，勾选 `悬浮画面`，作用域至少勾选：
   - ✅ **系统框架**（system_server）
   - ✅ **SystemUI**
   - ✅ 你的目标 App
3. **重启电视**（模块注入需要）
4. PC 端控制台：直接运行 `releases/FloatWinConsole.exe`，或用源码 `python pc/floatwin_gui.pyw`

### 使用

1. 控制台顶部连上电视（自动识别 / 扫描局域网 / 手动填 IP）
2. 「目标 App」选好（自动识别 / 列出应用 / 当前前台）
3. 点 **「零黑屏启动」** → 电视上就出现悬浮小窗
4. 在「悬浮窗」页调位置尺寸，或双击应用列表里的 App 直接启动

---

## 工作原理

模块在三个进程里协同工作。

### 1. 放行：system_server

真正的 PiP 门禁在 system_server 的 `ActivityRecord.supportsPictureInPicture()`。只在目标 App 进程里改 `mActivityInfo` 的 flag 是**无效**的（v1 版本就栽在这）。所以：

```java
findAndHookMethod("com.android.server.wm.ActivityRecord", ...,
    "supportsPictureInPicture", boolean.class, ...);
// beforeHookedMethod → param.setResult(true)
```

### 2. 摆放：SystemUI + TV BSP

这是本项目最关键的一个发现：**`PipBoundsState.mBounds` 只是记账值**，改它不会真的挪动窗口。

TV ROM 真正在摆放窗口的是：

```
TvPipBoundsAlgorithm#getTvPipPlacement()   ← 每次布局都会调
    └─ 捕获其中的 sBoundsController（TvPipBoundsController）
        └─ applyPlacementBounds(0, rect)   ← 真正的摆放入口
```

注意区分：`getEntryDestinationBounds()` 只在**进入 PiP 时调一次**，这也解释了早期版本「启动时位置对、之后拖不动」的现象。

### 3. 拖动 / 缩放：坐标文件轮询

控制台把 `x,y,w,h` 写进 `/data/local/tmp/floatwin_pip_pos`，SystemUI 里的模块守护线程每 250ms 读取并调用 `applyPlacementBounds` —— 所以是**实时**的，不重建 Activity。

> 早期版本用 `setPictureInPictureParams(setAspectRatio)` 缩放，会触发 Activity 重建并弹出系统起始窗口（黑底 + 图标），表现为"缩放时闪一下透明黑影"。现在缩放与拖动走同一条链路，无此问题。

### 4. 遥控器中转

App 在 PiP 里拿不到遥控器按键。流程：

```
system_server 拦截导航键（PhoneWindowManager / MiuiPhoneWindowManager 多候选探测）
    → 广播 RELAY_KEY
        → App 进程收到后调用自己的 dispatchKeyEvent()
```

### 5. 零黑屏启动

启动时窗口按 1920×1080 渲染约 300~750ms，这期间就是用户看到的"全屏一闪"。

难点是**窗口 alpha 挡不住 SurfaceView**（Flutter / 视频类 App 内容画在独立 Surface 上），而且 `Activity.makeVisible()` 会在 resume 时把 `decorView` 重新 `setVisibility(VISIBLE)`，**把一次性的隐形直接抹掉**。

所以 v33+ 的做法是**压制循环**：从隐形起每 25ms 重新压一次（窗口 alpha + decorView INVISIBLE + 递归隐藏 SurfaceView / TextureView / Flutter 表面），一直压到外框真的缩到 PiP 尺寸才放手。

### 6. 直通源前台保持

PiP 出现时系统会把它下面的 task 抬起来，而 pinned 栈下面坐的往往就是**桌面** —— 于是"回桌面 + HDMI 重握手 + 黑屏"。

模块通过 Android 标准 `TvInput` API 把已存在的 `ExternalSourceActivity` 挪回前台，关键是 **`REORDER_TO_FRONT | SINGLE_TOP`：只挪不建**，走 `onNewIntent` 而不是重建 Activity —— 重建会导致 HDMI 重握手黑屏。

---

## 目录结构

```
FloatWin-GPro27U/
├── README.md                  ← 你在这儿
├── LICENSE                    MIT
├── tv/                        TV 端（LSPosed 模块）
│   ├── AndroidManifest.xml
│   ├── assets/xposed_init
│   ├── src/com/gpro27u/floatwin/HookEntry.java   ← 全部 hook 逻辑（单文件）
│   └── build/
│       ├── build.sh           无 Gradle 构建链（javac → d8 → aapt2 → 签名）
│       └── api-82.jar         Xposed API（编译用）
├── pc/                        PC 端控制台
│   ├── floatwin_gui.pyw       源码（Python + tkinter，无第三方依赖）
│   ├── 启动控制台.bat
│   └── FloatWinConsole.spec   PyInstaller 打包配置
├── releases/                  预编译产物
│   ├── FloatWin-v*.apk        历代版本（v1 ~ v34）
│   └── FloatWinConsole.exe    Windows 控制台（免装 Python）
├── tools/
│   └── floatwin_backd.sh      电视端返回键守护（常驻 logcat 监听）
└── docs/
    ├── CONFIG.md              全部配置开关详解
    └── PRINCIPLE.md           原理与踩坑记录
```

---

## 配置开关

模块的行为全部由 `/data/local/tmp/` 下的小文件控制，**改完不用重启电视**（多数下次启动 App 即生效）。控制台界面上的按钮就是在写这些文件，也可以手动 `adb shell` 写。

常用几个：

| 文件 | 取值 | 作用 |
|---|---|---|
| `floatwin_pip_pos` | `x,y,w,h` | 悬浮窗位置与尺寸（实时生效） |
| `floatwin_pip_preset` | `phone` / `tablet` / `tv` | 预设布局 |
| `floatwin_key_relay` | `1` / `0` | 遥控器中转开关（默认开） |
| `floatwin_ghost` | `1` / `0` | 启动隐形 |
| `floatwin_home_restore` | `1` / `0` | 进 PiP 后把 HDMI 直通源拉回前台（默认开） |
| `floatwin_back_delegate` | `1` / `0` | 返回键交给外部执行 force-stop |

完整列表见 **[docs/CONFIG.md](docs/CONFIG.md)**。

---

## 从源码构建

### TV 端

```bash
cd tv
bash build/build.sh v34
# 产物：dist/FloatWin-v34.apk
```

无 Gradle，纯命令行：

```
javac(8) → d8 → aapt2 link → 注入 dex → zipalign → apksigner
```

需要的路径可用环境变量覆盖：`JAVA_HOME`、`ANDROID_SDK`、`PY`。首次构建会自动生成 `debug.keystore`。

### PC 端

```bash
# 直接运行
python pc/floatwin_gui.pyw

# 打包成 exe
pyinstaller pc/FloatWinConsole.spec
```

仅依赖 Python 标准库（tkinter）。

---

## 已知问题与局限

坦诚地讲，以下几项还没做到完美：

1. **遥控器返回键退场**：按返回时 App 自己 finish / kill，系统必然会抬起一个 task（抬的就是桌面）。目前**最稳定的方式是控制台上的「强制停止」按钮**。`tools/floatwin_backd.sh` 提供一个电视端常驻守护方案（监听日志 → `am force-stop`），但电视重启后需重新部署。欢迎提 PR 改进。
2. **移动 / 缩放偶发失效**：依赖 SystemUI 里的模块守护线程，该线程在 SystemUI 内存压力大时可能失效。控制台提供「重载 SystemUI」按钮修复（会黑屏 1~2 秒并短暂中断 HDMI，需手动确认）。
3. **启动瞬间的全屏**：Flutter 等 App 的内容画在独立 Surface 上，只能靠压制循环遮盖，极端情况下仍可能露出极短一瞬。
4. **机型适配**：TV BSP 的类名（`TvPipBoundsAlgorithm` 等）是私有实现，其它机型需按 [移植指南](#移植到其它机型) 重新探测。

---

## 移植到其它机型

1. 确认目标 ROM 的 PiP 实现类名：
   ```bash
   adb shell "cmd package list packages" >/dev/null
   # 反编译 SystemUI / 找 *PipBounds* 相关类
   ```
   本项目 `tools/scan_pip_classes.py` 思路可直接复用。
2. 修改 `HookEntry.java` 里的类名常量与 hook 点，模块已做**多候选探测**（找不到就逐个试，日志会告诉你命中了哪个）。
3. HDMI 直通源的组件名（小米系是 `com.xiaomi.mitv.tvplayer/.ExternalSourceActivity`）需换成你机型的，可用 `floatwin_hdmi_cmp` 指定而不改代码。

---

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) —— hook 框架
- [Aerial 桌面](https://github.com/pengchujin/aerial-tv) —— 配套推荐的电视桌面（本项目不含其代码）

---

## 许可

[MIT](LICENSE)。

## 免责声明

本项目需要 root 与 Xposed 框架，会修改系统进程行为。**请自担风险**：刷机 / 修改系统可能导致设备无法启动或失去保修。作者不对任何设备损坏或数据丢失负责。

请勿用于违反应用服务条款的场景。
