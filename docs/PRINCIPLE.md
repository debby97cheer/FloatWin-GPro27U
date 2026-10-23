# 原理与踩坑记录

这篇是把开发过程里**试错出来的结论**记下来。很多坑不看代码根本想不到，希望对你移植或改进有用。

---

## 一、改 `PipBoundsState.mBounds` 是无效的

`PipBoundsState.mBounds` 只是 SystemUI 的**记账值**。改它，UI 上看起来"位置变了"，但真窗口纹丝不动。

真正的摆放在 TV ROM 里是：

```
TvPipBoundsAlgorithm#getTvPipPlacement()      每次布局都调
   └─ 内部持有 sBoundsController（TvPipBoundsController）
       └─ applyPlacementBounds(int animDuration, Rect rect)   ← 真入口
```

另一个易混淆点：`getEntryDestinationBounds()` **只在进入 PiP 时调用一次**。早期版本 hook 它，表现就是"刚启动时位置对，之后怎么拖都不动"。

**结论**：hook `getTvPipPlacement()` 拿到 controller 实例，之后所有实时摆放都走 `applyPlacementBounds(0, rect)`。

---

## 二、缩放不要用 `setAspectRatio`

用 `setPictureInPictureParams(new Builder().setAspectRatio(r))` 缩放，会触发 **Activity 重建**，系统弹出起始窗口（黑底 + 应用图标），用户看到的就是"缩放时闪一下透明黑影"。

早期试图用 `fadeOut(alpha=0)` 去遮它 —— 反而自己变成了一块透明黑影。

**结论**：缩放必须与拖动走同一条链路（`applyPlacementBounds`），不碰 PiP params。

---

## 三、"黑色半透明残影"是 SystemUI 的装饰层

PiP 周围那圈背景不是 App 画的，是 SystemUI 单独画的 `TvPipBackgroundView` / `TvPipMenuView`。App 侧把窗口 alpha 调到 0 **管不到它**。

要收起它必须在 **`wmshell.main` 线程**操作（`setVisibility(GONE)` + `setAlpha(0)`），用主线程会抛 `CalledFromWrongThreadException`。

---

## 四、gravity 会把窗口拽回右上角

TV 版摆放会读取 gravity。早期实现一律返回 `TOP|RIGHT`(53)，于是拖到哪儿都会被拉回右上角。

**结论**：按矩形中心算 gravity —— 左/中/右 × 上/中/下。

---

## 五、一次性隐形会被 framework 抹掉

`Activity.makeVisible()`（由 `ActivityThread.handleResumeActivity` 调用）会执行 `mDecor.setVisibility(VISIBLE)`。

所以"启动时设一次 INVISIBLE"等于白设 —— 你设完，framework 立刻给你改回来。日志上会看到隐形确实执行了，但用户照样看到全屏。

**结论**：用**压制循环**，每 25ms 重新压一次，直到外框真的就位。同时注意别起出多条循环共用计数器（会让循环提前"到点"收工，正好把最后那段全屏露出来 —— 这个 bug 真实发生过）。

---

## 六、窗口 alpha 挡不住 SurfaceView

Flutter / 视频类 App 的内容画在**独立 Surface** 上，不吃窗口 alpha。窗口透明了，内容照样满屏显示。

**结论**：隐形时要递归把 `SurfaceView` / `TextureView` / 类名含 Flutter 的 View 一并 `INVISIBLE`。

---

## 七、重建 HDMI 直通源 = 黑屏

用纯 `FLAG_ACTIVITY_NEW_TASK` 去 start `ExternalSourceActivity`，会把直通源**重新创建**一次 → HDMI 重新握手 → 画面黑一下。

**结论**：用 `REORDER_TO_FRONT | SINGLE_TOP`，只把已存在的 Activity **挪回**前台，走 `onNewIntent` 不重建。

---

## 八、返回键为什么总回到桌面

PiP（pinned 栈）退场时，系统会把它下面的 task 抬起来 —— 而 pinned 下面坐的往往就是**桌面**。所以只要 App 自己 finish，桌面必然被抬出来。

而 App 自己 `killProcess` 也不行：带前台服务的 App 会被系统**拉活**（日志实证：task 已移除，5 秒后它还在收遥控按键）。

**目前的对策**：
- 最稳的是外部执行 `am force-stop`（PC 控制台的「强制停止」按钮，实测不黑屏）
- `tools/floatwin_backd.sh` 提供电视端常驻守护：监听模块打的 `BACKKEY <pkg>` 日志 → 执行 `am force-stop`
- 模块侧 `floatwin_back_delegate` 打开后，App 只吞键 + 打日志，不做任何退场动作

这个问题**尚未完美解决**，欢迎 PR。

---

## 九、App 进程写不了 `/data/local/tmp`

受 SELinux 限制，早期用"写文件当信号"的通道**从未生效过**（文件从未被创建）。

**结论**：跨进程通信走广播，不要用文件信号。（控制台上有「修复 SELinux」按钮可放宽限制。）

---

## 十、`am start-activity` 没有 `--bounds` 参数

想让窗口"第一帧就是小尺寸"是做不到的 —— 查过 `am` 的完整用法，没有 bounds 参数。所以只能靠启动隐形。

---

## 十一、别连续 kill SystemUI

这台电视 2.8G 内存常驻 2.7G。**连续 kill SystemUI 会导致内存枯竭 → 系统杀 tvplayer → HDMI 黑屏 → 框架重启（zygote PID 变化）→ 全部输入源丢失**。

恢复办法：
```bash
adb shell "am start -a com.xiaomi.mitv.tvplayer.EXTSRC_PLAY \
  -n com.xiaomi.mitv.tvplayer/.ExternalSourceActivity"
```
以及清缓存：
```bash
adb shell "su -c 'sync; echo 3 > /proc/sys/vm/drop_caches'"
```

**结论**：要重载 SystemUI 就只 kill 一次，且先清缓存。

---

## 十二、PC 端"无信号"可能是虚拟显示器抢了桌面

排查 HDMI 无信号时别忘了检查 Windows 侧：远程控制软件（如网易 UU 远程）会装 `GameViewer Virtual Display Adapter` 之类的**虚拟显卡**，伪造一台显示器抢走桌面，导致画面输出到"看不见的屏幕"。

```powershell
Get-PnpDevice -Class Display | Where-Object {$_.Status -eq 'OK'}
Disable-PnpDevice -InstanceId "ROOT\DISPLAY\0000" -Confirm:$false
```

与 EDID、杜比视界、本模块都无关 —— 但这个坑排查了很久。
