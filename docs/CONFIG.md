# 配置开关详解

模块的行为全部由 `/data/local/tmp/` 下的小文件控制。写文件用：

```bash
adb shell "echo 1 > /data/local/tmp/floatwin_ghost"
```

或用 PC 控制台界面上的按钮（界面按钮就是在写这些文件）。

> **注意**：多数开关在**下次启动目标 App 时生效**；位置 / 尺寸类是实时生效的。
> 若电视读不到这些文件（SELinux 限制），控制台上有「修复 SELinux」按钮。

---

## 位置与尺寸

| 文件 | 格式 | 说明 |
|---|---|---|
| `floatwin_pip_pos` | `x,y,w,h` | 绝对像素坐标与尺寸。SystemUI 里的守护线程每 250ms 轮询，**改完即刻生效**，不用重启 App |
| `floatwin_pip_size` | `w,h` | 只指定尺寸 |
| `floatwin_pip_scale` | 浮点数 | 缩放系数 |
| `floatwin_pip_preset` | `phone` / `tablet` / `tv` | 预设布局。`phone` = 9:16 竖屏 + 411dp + sw411，让手机 App 用手机布局 |

## 启动行为

| 文件 | 默认 | 说明 |
|---|---|---|
| `floatwin_ghost` | 关 | 启动隐形：启动期把窗口与 View 树压住（含 SurfaceView），直到外框缩到 PiP 尺寸才显形。用于消除"启动先全屏再缩放" |
| `floatwin_no_anim` | 关 | 关闭进 PiP 的动画 |

## 遥控器

| 文件 | 默认 | 说明 |
|---|---|---|
| `floatwin_key_relay` | **开** | 遥控中转：system_server 抓导航键 → 广播 → App 侧 `dispatchKeyEvent`，可操作悬浮窗里的 App |
| `floatwin_key_target` | 包名 | 中转的目标包名 |
| `floatwin_back_exit` | 开 | 返回键退场接管总开关（写 `0` 关闭，恢复系统默认行为） |
| `floatwin_back_delegate` | 关 | 返回键交给外部执行 `force-stop`：App 只吞键并打一行 `BACKKEY <pkg>` 日志，由守护进程或 PC 控制台执行强停 |
| `floatwin_back_front` | 开 | 返回时先把 HDMI 直通源**挪回**前台（只挪不重建） |
| `floatwin_back_hdmi` | 关 | 返回时重新 start 直通源 Activity。**默认关** —— 实测重建会触发 HDMI 重握手导致黑屏 |
| `floatwin_back_hide` | 关 | 返回时先把窗口变透明再收尾 |

## 前台与 HDMI 直通

| 文件 | 默认 | 说明 |
|---|---|---|
| `floatwin_home_restore` | **开** | 进 PiP 后自动把 HDMI 直通源拉回前台，避免退回电视桌面 / HDMI 黑屏重同步 |
| `floatwin_hdmi_id` | 自动 | 指定 HDMI 输入源 ID |
| `floatwin_hdmi_cmp` | 自动 | 指定直通源组件，如 `com.xiaomi.mitv.tvplayer/.ExternalSourceActivity` |

## 显示参数

| 文件 | 说明 |
|---|---|
| `floatwin_target_dp` | 覆盖目标 App 的 `densityDpi`（让内容显示得全） |
| `floatwin_pip_sw` | 覆盖 `smallestScreenWidthDp` |
| `floatwin_pip_orient` | 强制屏幕方向 |

## 焦点 / 调试

| 文件 | 说明 |
|---|---|
| `floatwin_focus_grab` | 焦点强占实验（默认关） |
| `floatwin_pip_focus` / `floatwin_pip_hasfocus` | 焦点状态上报 |
| `floatwin_pip_query` | `touch` 一下，查询当前实际 bounds，结果写入 `floatwin_pip_actual` |
| `floatwin_pip_actual` | 模块回写的实际窗口尺寸（读） |
| `floatwin_pip_active` | PiP 是否活跃（读） |
| `floatwin_pip_park` | 收起状态标记（读） |

---

## 广播 Action（模块内部 / 进阶用）

| Action | 用途 |
|---|---|
| `com.gpro27u.floatwin.RELAY_KEY` | 遥控按键中转 |
| `com.gpro27u.floatwin.HDMI_FRONT` | 把 HDMI 直通源拉回前台 |
| `com.gpro27u.floatwin.KILL_ME` | 请求强停指定包 |
| `com.gpro27u.floatwin.PARK` / `UNPARK` | 收起 / 恢复 PiP 装饰层 |
| `com.gpro27u.floatwin.PIP_REQ` / `PIP_EXIT` | 请求进入 / 退出 PiP |
| `com.gpro27u.floatwin.SET_TARGET` | 设置目标包名 |

> 这些是模块内部通信用的，普通用户通过控制台操作即可，不必手动发广播。
