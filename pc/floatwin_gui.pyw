# -*- coding: utf-8 -*-
"""
FloatWin 悬浮窗控制台 —— Windows 桌面 GUI

把电视端 PiP 悬浮窗的所有 adb 调参命令做成可视化面板：
  窗口预览拖动 / 预设 / 尺寸 / 放大 / 逻辑宽度 / sw / 方向 / 位置 / 日志 / 安装维护

运行：双击同目录的「启动控制台.bat」，或 py -3 floatwin_gui.pyw
"""
import os
import re
import sys
import json
import time
import queue
import socket
import subprocess
import threading
import tkinter as tk
from tkinter import ttk, messagebox, filedialog

TITLE = "FloatWin 悬浮窗控制台 v2.2"
if getattr(sys, "frozen", False):
    # PyInstaller 打包后 __file__ 指向临时目录，配置必须落到 exe 旁边才能持久
    HERE = os.path.dirname(os.path.abspath(sys.executable))
else:
    HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG = os.path.join(HERE, "floatwin_gui.json")
NO_WINDOW = 0x08000000

DEFAULT_PKG = "com.appshub.bettbox"
PKG_MODULE = "com.gpro27u.floatwin"

# 参数文件 → (默认值, 说明)
PARAMS = {
    "floatwin_pip_preset": ("tv",     "窗口预设 tv/phone"),
    "floatwin_pip_size":   ("",       "窗口像素 宽x高（最高优先级，留空=用预设/放大）"),
    "floatwin_pip_scale":  ("150",    "放大百分比 100-300"),
    "floatwin_target_dp":  ("",       "逻辑宽度 dp（留空=随预设 960/411）"),
    "floatwin_pip_sw":     ("",       "smallestScreenWidthDp（留空=不改）"),
    "floatwin_pip_orient": ("",       "0=自动 1=竖屏 2=横屏（留空=不改）"),
    "floatwin_pip_pos":    ("tr",     "位置 tl/tr/bl/br/c，或 x,y 绝对坐标"),
}

PRESETS = {
    "电视横屏 tv":   dict(preset="tv",    size="",       dp="",   sw="",   orient="0", note="960dp 横屏，适合原生 TV 应用"),
    "手机竖屏 phone": dict(preset="phone", size="",       dp="411", sw="411", orient="1", note="9:16 + 411dp + sw411，手机 App 专用"),
    "小窗 851x479":  dict(preset="tv",    size="851x479", dp="960", sw="",   orient="0", note="放大后的横屏，看得清"),
    "窄条 400x711":  dict(preset="phone", size="400x711", dp="360", sw="360", orient="1", note="小竖屏，占地方少"),
    "原始 567x319":  dict(preset="tv",    size="567x319", dp="960", sw="",   orient="0", note="系统默认 PiP 尺寸"),
}


# ---------------------------------------------------------------- 主题
FONT = "Microsoft YaHei UI"
FONT_MONO = "Consolas"

THEMES = {
    "light": dict(
        bg="#F4F6FA", card="#FFFFFF", fg="#1B2430", sub="#64748B",
        accent="#2F6BFF", accent_fg="#FFFFFF", hover="#E8EFFB",
        danger="#E5484D", danger_fg="#FFFFFF", danger_hover="#F3C3C6",
        border="#E2E8F0", field="#FFFFFF",
        tab_bg="#E9EEF6", tab_fg="#64748B", tab_sel_bg="#FFFFFF", tab_sel_fg="#1B2430",
        log_bg="#101417", log_fg="#D7DEE8",
    ),
    "dark": dict(
        bg="#151A21", card="#1B212B", fg="#E6EDF3", sub="#94A3B8",
        accent="#4C8DFF", accent_fg="#0B1220", hover="#263141",
        danger="#FF6369", danger_fg="#2A0E11", danger_hover="#5A2126",
        border="#2A3340", field="#11161D",
        tab_bg="#11161D", tab_fg="#94A3B8", tab_sel_bg="#1B212B", tab_sel_fg="#E6EDF3",
        log_bg="#0B0F14", log_fg="#C9D4E2",
    ),
}


def apply_theme(root, style, mode):
    """现代扁平风：卡片 + 蓝主色 + 细边框控件（在 ttk 的 clam 主题上改）"""
    t = THEMES.get(mode, THEMES["light"])
    try:
        style.theme_use("clam")
    except Exception:
        pass
    root.configure(bg=t["bg"])
    style.configure(".", background=t["bg"], foreground=t["fg"],
                    fieldbackground=t["card"], font=(FONT, 9),
                    bordercolor=t["border"], darkcolor=t["border"],
                    lightcolor=t["border"], troughcolor=t["bg"])
    style.configure("TFrame", background=t["bg"])
    style.configure("Card.TFrame", background=t["card"])
    style.configure("TLabel", background=t["bg"], foreground=t["fg"])
    style.configure("Card.TLabel", background=t["card"], foreground=t["fg"])
    style.configure("Sub.TLabel", background=t["bg"], foreground=t["sub"])
    style.configure("Subcard.TLabel", background=t["card"], foreground=t["sub"])
    style.configure("H1.TLabel", background=t["bg"], foreground=t["fg"],
                    font=(FONT, 10, "bold"))
    style.configure("TLabelframe", background=t["bg"], bordercolor=t["border"],
                    relief="flat", borderwidth=1)
    style.configure("TLabelframe.Label", background=t["bg"], foreground=t["sub"],
                    font=(FONT, 9, "bold"))
    style.configure("TNotebook", background=t["bg"], borderwidth=0, tabmargins=(0, 6, 0, 0))
    style.configure("TNotebook.Tab", background=t["tab_bg"], foreground=t["tab_fg"],
                    padding=(16, 9), borderwidth=0, font=(FONT, 9))
    style.map("TNotebook.Tab",
              background=[("selected", t["tab_sel_bg"])],
              foreground=[("selected", t["tab_sel_fg"])])
    style.configure("TButton", background=t["card"], foreground=t["fg"],
                    borderwidth=1, bordercolor=t["border"], padding=(12, 7),
                    relief="flat", focusthickness=0, font=(FONT, 9))
    style.map("TButton",
              background=[("active", t["hover"]), ("pressed", t["accent"])],
              foreground=[("active", t["fg"]), ("pressed", t["accent_fg"])])
    style.configure("Accent.TButton", background=t["accent"], foreground=t["accent_fg"],
                    borderwidth=0)
    style.map("Accent.TButton",
              background=[("active", t["accent"]), ("pressed", t["accent"])],
              foreground=[("active", t["accent_fg"]), ("pressed", t["accent_fg"])])
    style.configure("Danger.TButton", background=t["danger"], foreground=t["danger_fg"],
                    borderwidth=0)
    style.map("Danger.TButton",
              background=[("active", t["danger_hover"]), ("pressed", t["danger"])],
              foreground=[("active", t["danger_fg"]), ("pressed", t["danger_fg"])])
    style.configure("TEntry", fieldbackground=t["field"], foreground=t["fg"],
                    bordercolor=t["border"], lightcolor=t["border"],
                    padding=(8, 6), borderwidth=1, insertcolor=t["fg"])
    style.map("TEntry", bordercolor=[("focus", t["accent"])],
              lightcolor=[("focus", t["accent"])])
    style.configure("TCombobox", fieldbackground=t["field"], foreground=t["fg"],
                    bordercolor=t["border"], padding=(8, 6), arrowsize=14)
    style.map("TCombobox", bordercolor=[("focus", t["accent"])],
              lightcolor=[("focus", t["accent"])])
    style.configure("TCheckbutton", background=t["bg"], foreground=t["fg"],
                    padding=(4, 4), borderwidth=0)
    style.configure("Card.TCheckbutton", background=t["card"], foreground=t["fg"])
    style.configure("TRadiobutton", background=t["bg"], foreground=t["fg"],
                    padding=(4, 2), borderwidth=0)
    style.map("TCheckbutton", background=[("active", t["bg"])],
              indicatorcolor=[("selected", t["accent"])])
    style.map("Card.TCheckbutton", background=[("active", t["card"])])
    style.map("TRadiobutton", background=[("active", t["bg"])],
              indicatorcolor=[("selected", t["accent"])])
    style.configure("TProgressbar", background=t["accent"], troughcolor=t["bg"],
                    borderwidth=0, thickness=6)
    style.configure("TScrollbar", background=t["bg"], borderwidth=0, arrowsize=13,
                    troughcolor=t["bg"])
    style.map("TScrollbar", background=[("active", t["border"])])
    style.configure("TSeparator", background=t["border"])
    return t


# ---------------------------------------------------------------- adb 封装
class ScrollFrame(ttk.Frame):
    """v33：整页可纵向滚动的容器。
    窗口拉不大时（笔记本/小屏）下面的按钮也不会被裁掉。"""

    def __init__(self, master, **kw):
        ttk.Frame.__init__(self, master, **kw)
        self.canvas = tk.Canvas(self, highlightthickness=0, borderwidth=0)
        self.vsb = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=self.vsb.set)
        self.vsb.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.inner = ttk.Frame(self.canvas)
        self.win = self.canvas.create_window((0, 0), window=self.inner, anchor="nw")
        self.inner.bind("<Configure>",
                        lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", self._on_cfg)
        # bind_all：鼠标停在页面任何位置都能滚（控件自身的滚轮绑定优先级更高，不冲突）
        self.bind_all("<MouseWheel>", self._on_wheel)
        self.bind_all("<Button-4>", lambda e: self.canvas.yview_scroll(-1, "units"))
        self.bind_all("<Button-5>", lambda e: self.canvas.yview_scroll(1, "units"))

    def _on_cfg(self, e):
        self.canvas.itemconfig(self.win, width=e.width)
        # 内容比可视区矮就撑满，比可视区高就保持自然高度（交给滚动）
        req = self.inner.winfo_reqheight()
        self.canvas.itemconfig(self.win, height=max(req, e.height))

    def _on_wheel(self, e):
        try:
            self.canvas.yview_scroll(int(-1 * (e.delta / 120)), "units")
        except Exception:
            pass


class Adb:
    # 这些子命令不带设备号，不能插 -s
    GLOBAL_CMDS = {"devices", "connect", "disconnect", "start-server",
                   "kill-server", "version", "help"}

    def __init__(self, exe, serial=""):
        self.exe = exe
        self.serial = serial

    def _argv(self, args):
        args = list(args)
        if self.serial and args and args[0] not in self.GLOBAL_CMDS:
            args = ["-s", self.serial] + args
        return args

    def run(self, *args, timeout=20):
        try:
            p = subprocess.run([self.exe] + self._argv(args), capture_output=True,
                               timeout=timeout, creationflags=NO_WINDOW)
            out = (p.stdout or b"") + (p.stderr or b"")
            return out.decode("utf-8", "replace")
        except Exception as e:
            return "ERR: %s" % e

    def sh(self, cmd, timeout=20):
        """cmd 为设备端 shell 字符串"""
        return self.run("shell", cmd, timeout=timeout)

    def devices(self):
        out = self.run("devices")
        r = []
        for line in out.splitlines():
            m = re.match(r"^(\S+)\s+device\s*$", line.strip())
            if m:
                r.append(m.group(1))
        return r

    def devices_detail(self):
        """返回 [(serial, usb?, model)]，只含 state=device 的"""
        out = self.run("devices", "-l")
        r = []
        for line in out.splitlines():
            line = line.strip()
            if not line or line.lower().startswith("list of"):
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            serial, state = parts[0], parts[1]
            if state != "device":
                continue
            model = ""
            for kv in parts[2:]:
                if kv.startswith("model:"):
                    model = kv.split(":", 1)[1]
            r.append((serial, ":" not in serial, model))
        return r


def find_adb():
    cands = [
        r"C:\platform-tools\adb.exe",
        os.path.join(os.path.dirname(HERE), "platform-tools", "adb.exe"),
        os.path.join(HERE, "adb.exe"),
    ]
    for c in cands:
        if os.path.isfile(c):
            return c
    for d in os.environ.get("PATH", "").split(os.pathsep):
        p = os.path.join(d, "adb.exe")
        if os.path.isfile(p):
            return p
    return ""


# ---------------------------------------------------------------- 主界面
class App:
    def __init__(self, root):
        self.root = root
        root.title(TITLE)
        root.geometry("980x720")
        root.minsize(900, 640)

        self.cfg = self.load_cfg()
        # 现代主题（浅/深），默认浅色，切换后写进配置记住
        self.theme = self.cfg.get("theme", "light")
        self.style = ttk.Style(root)
        self.t = apply_theme(root, self.style, self.theme)
        self.adb_exe = self.cfg.get("adb") or find_adb()
        self.ip = self.cfg.get("ip", "")
        self.pkg = self.cfg.get("pkg", DEFAULT_PKG)
        self.serial = self.cfg.get("serial", "")
        self.adb = Adb(self.adb_exe, self.serial)

        self.screen_w = 1920
        self.screen_h = 1080
        self.win = dict(x=0, y=0, w=547, h=972)
        self.mod_ver = 0
        self.sending = False
        self.last_sent = None
        self.drag_latest = None
        self.log_q = queue.Queue()
        self.back_watch = False          # v33：返回键接管（logcat → am force-stop）
        self.back_watch_proc = None

        self.build_top()
        self.build_tabs()
        self.build_bottom()
        self.apply_native_theme()      # 原生 tk 控件（列表/日志/预览）跟着主题走

        self.root.after(300, self.tick_pusher)
        self.root.after(600, self.auto_connect)
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        # 默认开：用户要的就是"返回 = 强制停止"，这条路径实测不黑屏、不回桌面
        if self.cfg.get("back_watch", True):
            self.root.after(1500, self.start_back_watch)
        # 电视端守护：装一次管到电视重启，不依赖电脑
        if self.cfg.get("back_daemon", True):
            self.root.after(3000, lambda: self.daemon_install(auto=True))

    # ------------------------------------------------------------ 配置
    def load_cfg(self):
        try:
            with open(CONFIG, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}

    def on_close(self):
        # 关窗前把 logcat 常驻进程收掉，否则会留下一个孤儿 adb 进程
        self.back_watch = False
        try:
            if self.back_watch_proc:
                self.back_watch_proc.kill()
        except Exception:
            pass
        try:
            self.root.destroy()
        except Exception:
            pass

    def save_cfg(self):
        self.cfg["adb"] = self.adb_exe
        self.cfg["ip"] = self.ip
        self.cfg["pkg"] = self.pkg
        self.cfg["serial"] = self.serial
        try:
            with open(CONFIG, "w", encoding="utf-8") as f:
                json.dump(self.cfg, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    # ------------------------------------------------------------ 顶部
    def build_top(self):
        # 顶部做成一张白卡片：两行工具条，比原来的裸 grid 干净得多
        f = ttk.Frame(self.root, padding=10, style="Card.TFrame")
        f.pack(fill="x", padx=8, pady=(8, 4))

        r0 = ttk.Frame(f, style="Card.TFrame")
        r0.pack(fill="x")
        ttk.Label(r0, text="adb", style="Card.TLabel").pack(side="left")
        self.v_adb = tk.StringVar(value=self.adb_exe)
        ttk.Entry(r0, textvariable=self.v_adb, width=30).pack(side="left", padx=(6, 4))
        ttk.Button(r0, text="浏览", width=6, command=self.pick_adb).pack(side="left")

        ttk.Label(r0, text="电视 IP", style="Card.TLabel").pack(side="left", padx=(14, 0))
        self.v_ip = tk.StringVar(value=self.ip)
        ttk.Entry(r0, textvariable=self.v_ip, width=15).pack(side="left", padx=(6, 0))
        ttk.Label(r0, text=":5555", style="Subcard.TLabel").pack(side="left")

        ttk.Button(r0, text="自动识别", width=9, command=self.auto_connect).pack(side="left", padx=(12, 0))
        ttk.Button(r0, text="扫描局域网", width=10, command=self.scan_lan).pack(side="left", padx=4)

        self.v_theme_btn = tk.StringVar(value=("深色模式" if self.theme == "light" else "浅色模式"))
        ttk.Button(r0, textvariable=self.v_theme_btn, width=10,
                   command=self.toggle_theme).pack(side="right")
        self.v_status = tk.StringVar(value="未连接")
        self.l_status = ttk.Label(r0, textvariable=self.v_status, style="Card.TLabel")
        self.l_status.pack(side="right", padx=(0, 12))

        r1 = ttk.Frame(f, style="Card.TFrame")
        r1.pack(fill="x", pady=(8, 0))
        ttk.Label(r1, text="目标 App", style="Card.TLabel").pack(side="left")
        self.v_pkg = tk.StringVar(value=self.pkg)
        self.cb_pkg = ttk.Combobox(r1, textvariable=self.v_pkg, width=26,
                                   values=[self.pkg], state="normal")
        self.cb_pkg.pack(side="left", padx=(6, 4))
        self.app_list = [self.pkg]

        ttk.Button(r1, text="自动识别", width=9, command=self.auto_detect_app).pack(side="left")
        ttk.Button(r1, text="列出应用", width=9, command=self.refresh_apps).pack(side="left", padx=4)
        ttk.Button(r1, text="当前前台", width=9, command=self.detect_foreground).pack(side="left")
        # v31：一键强停（等价烟雨 OTA 工具箱里的"强制停止"，不会触发 HDMI 重协商）
        ttk.Button(r1, text="强制停止", width=10, style="Danger.TButton",
                   command=lambda: self.force_stop_app()).pack(side="left", padx=(12, 0))

        ttk.Label(r1, text="设备", style="Card.TLabel").pack(side="left", padx=(14, 0))
        self.v_dev = tk.StringVar(value=self.serial or "(自动)")
        self.cb_dev = ttk.Combobox(r1, textvariable=self.v_dev, width=20,
                                   values=["(自动)"], state="readonly")
        self.cb_dev.pack(side="left", padx=(6, 0))
        self.cb_dev.bind("<<ComboboxSelected>>", self.on_pick_dev)

        self.v_ver = tk.StringVar(value="模块: 未知")
        ttk.Label(r1, textvariable=self.v_ver, style="Subcard.TLabel").pack(side="left", padx=(12, 0))

        self.v_drag = tk.StringVar(value="")
        ttk.Label(f, textvariable=self.v_drag, style="Subcard.TLabel").pack(anchor="w", pady=(6, 0))

    # ------------------------------------------------------------ 强制停止 / 主题
    def force_stop_app(self, pkg=None):
        """一键强停：和烟雨 OTA 工具箱里那个一样。
        只停 App，不碰 HDMI 前台 —— 所以不会像"拉起直通源"那样触发重协商黑屏。"""
        p = (pkg or "").strip() or (self.v_pkg.get() or "").strip()
        if not p:
            self.status("先选一个目标 App", False)
            return
        if not self.check_ready(silent=True):
            return

        def work():
            out = self.adb.sh("am force-stop %s" % p, timeout=20)
            pid = self.adb.sh("pidof %s" % p, timeout=10).strip()
            msg = "[强制停止] %s -> %s" % (p, out.strip().replace("\n", " | ") or "已执行")
            msg += "\n  进程仍在（pid %s）——多半是后台服务又把它拉起来了" % pid if pid \
                else "\n  进程已不在，悬浮窗应已消失"
            self.log_q.put(("text", msg))
        threading.Thread(target=work, daemon=True).start()
        self.log_line("[强制停止] 正在对 %s 执行 am force-stop…" % p)

    def toggle_theme(self):
        self.theme = "dark" if self.theme == "light" else "light"
        self.cfg["theme"] = self.theme
        self.save_cfg()
        self.t = apply_theme(self.root, self.style, self.theme)
        self.apply_native_theme()
        self.v_theme_btn.set("浅色模式" if self.theme == "dark" else "深色模式")

    def apply_native_theme(self):
        """tk 原生控件（Listbox / Text / Canvas）不吃 ttk 样式，得单独刷一遍"""
        t = self.t
        try:
            self.sc.canvas.configure(bg=t["bg"], highlightthickness=0)   # v33 滚动区
        except Exception:
            pass
        try:
            self.logbox.configure(bg=t["log_bg"], fg=t["log_fg"],
                                  insertbackground=t["log_fg"],
                                  relief="flat", highlightthickness=0)
        except Exception:
            pass
        try:
            self.app_list.configure(bg=t["card"], fg=t["fg"],
                                    selectbackground=t["accent"],
                                    selectforeground=t["accent_fg"],
                                    highlightbackground=t["border"],
                                    highlightthickness=1, relief="flat")
        except Exception:
            pass
        try:
            self.help_text.configure(bg=t["card"], fg=t["fg"],
                                     relief="flat", highlightthickness=0)
        except Exception:
            pass
        try:
            # 预览屏代表"电视画面"，保持纯黑最直观
            self.canvas.configure(bg="#000000", highlightbackground=t["border"])
        except Exception:
            pass

    def set_pkg_list(self, pkgs, cur=None):
        seen, out = set(), []
        for p in pkgs:
            p = (p or "").strip()
            if p and p not in seen:
                seen.add(p)
                out.append(p)
        if not out:
            return False
        if cur and cur not in out:
            out.insert(0, cur)
        self.app_list = out
        self.cb_pkg["values"] = out
        if cur:
            self.v_pkg.set(cur)
            self.pkg = cur
        self.save_cfg()
        return True

    # ------------------------------------------------------------ 电视 IP 自动识别
    def auto_connect(self):
        """先看 adb devices；没有就尝试上次 IP，再不行提示扫描。多设备时自动挑一台并锁定 -s"""
        self.adb_exe = self.v_adb.get().strip()
        self.adb = Adb(self.adb_exe, self.serial)
        if not self.adb_exe or not os.path.isfile(self.adb_exe):
            self.status("adb.exe 路径无效", False)
            return

        def work():
            det = self.adb.devices_detail()
            devs = [d[0] for d in det]
            ip = ""
            for s, usb, model in det:
                if ":" in s:
                    ip = s.split(":")[0]
                    break
            tried = ""
            if not devs:
                last = (self.cfg.get("ip") or "").strip()
                if last:
                    tried = self.adb.run("connect", "%s:5555" % last).strip()
                    det = self.adb.devices_detail()
                    devs = [d[0] for d in det]
                    if devs:
                        ip = last
            self.log_q.put(("devinfo", (devs, ip, tried, det)))
        threading.Thread(target=work, daemon=True).start()
        self.root.after(120, self.drain)

    # ------------------------------------------------------------ 设备选择
    def set_serial(self, s):
        self.serial = s or ""
        self.adb.serial = self.serial
        self.v_dev.set(self.serial or "(自动)")
        self.save_cfg()

    def on_pick_dev(self, _e=None):
        v = self.v_dev.get().strip()
        self.set_serial("" if v == "(自动)" else v)
        if self.serial:
            self.log_line("[设备] 已锁定 %s（后续所有 adb 命令都带 -s，不会再报 more than one device）" % self.serial)
            self.probe()

    def pick_device(self, det):
        """多设备时挑一台：上次用过的 > USB 且像本电视 > USB > 网络且像本电视 > 第一个"""
        if not det:
            return ""
        if self.serial and any(d[0] == self.serial for d in det):
            return self.serial
        serials = [d[0] for d in det]

        def is_tv(d):
            return ("MiTV" in (d[2] or "")) or ("beekeeper" in (d[2] or "")) or ("MFFU" in (d[2] or ""))
        usb = [d for d in det if d[1]]
        for d in usb:
            if is_tv(d):
                return d[0]
        if usb:
            return usb[0][0]
        for d in det:
            if is_tv(d):
                return d[0]
        return serials[0]

    def on_devinfo(self, devs, ip, tried, det=None):
        det = det or [(d, ":" not in d, "") for d in devs]
        # 下拉框列出所有设备
        vals = ["(自动)"] + [d[0] for d in det]
        self.cb_dev["values"] = vals
        chosen = self.pick_device(det)
        if len(devs) > 1:
            self.log_line("[自动识别] 发现 %d 台设备：%s；已自动锁定 %s（可在「设备」下拉切换）"
                          % (len(devs), ", ".join(devs), chosen))
        if chosen:
            self.set_serial(chosen)
        if tried:
            self.log_line("[自动识别] 尝试上次IP %s -> %s" % (self.cfg.get("ip"), tried.replace("\n", " | ")))
        if ip:
            self.v_ip.set(ip)
            self.ip = ip
            self.save_cfg()
        if devs:
            self.log_line("[自动识别] 已连接设备: %s" % ", ".join(devs))
            if not ip:
                self.log_line("[自动识别] USB 连接，IP 可留空")
            self.probe()
        else:
            self.status("没找到设备（点「扫描局域网」）", False)
            self.log_line("[自动识别] 没找到设备。电视需开 ADB 调试（设置→关于→连点版本号→开发者选项→ADB 调试）")

    def default_gateway(self):
        try:
            out = subprocess.run(["ipconfig"], capture_output=True,
                                 creationflags=NO_WINDOW).stdout.decode("gbk", "replace")
        except Exception:
            return ""
        gw = ""
        for line in out.splitlines():
            if ("网关" in line or "Gateway" in line) and ":" in line:
                m = re.search(r"(\d+\.\d+\.\d+\.\d+)", line)
                if m:
                    gw = m.group(1)
        return gw

    def scan_lan(self):
        """扫局域网里开着 5555 端口的设备（ARP 表 + 网关同网段）"""
        self.adb_exe = self.v_adb.get().strip()
        self.adb = Adb(self.adb_exe, self.serial)
        if not self.adb_exe or not os.path.isfile(self.adb_exe):
            self.status("adb.exe 路径无效", False)
            return
        self.status("扫描中…", False)
        self.log_line("[扫描] 正在找局域网里开 5555 端口的设备…")

        def work():
            cands = set()
            last = (self.cfg.get("ip") or "").strip()
            if last:
                cands.add(last)
            gw = self.default_gateway()
            if gw:
                self.log_q.put(("text", "[扫描] 本机网关 %s" % gw))
                pre = gw.rsplit(".", 1)[0]
                for i in range(2, 40):
                    cands.add("%s.%d" % (pre, i))
            try:
                arp = subprocess.run(["arp", "-a"], capture_output=True,
                                      creationflags=NO_WINDOW).stdout.decode("gbk", "replace")
                for m in re.finditer(r"\b(\d+\.\d+\.\d+\.\d+)\b", arp):
                    ip = m.group(1)
                    if ip.endswith(".255") or ip.endswith(".0"):
                        continue
                    cands.add(ip)
            except Exception:
                pass
            cands = sorted(cands, key=lambda s: [int(x) for x in s.split(".")])
            open_ips = []
            lock = threading.Lock()

            def probe_ip(ip):
                s = socket.socket()
                s.settimeout(0.45)
                try:
                    if s.connect_ex((ip, 5555)) == 0:
                        with lock:
                            open_ips.append(ip)
                except Exception:
                    pass
                finally:
                    try:
                        s.close()
                    except Exception:
                        pass

            ts = []
            for ip in cands:
                t = threading.Thread(target=probe_ip, args=(ip,), daemon=True)
                t.start()
                ts.append(t)
                if len(ts) >= 48:
                    for x in ts:
                        x.join()
                    ts = []
            for x in ts:
                x.join()

            found = []
            for ip in sorted(open_ips, key=lambda s: [int(x) for x in s.split(".")]):
                out = self.adb.run("connect", "%s:5555" % ip, timeout=8)
                self.log_q.put(("text", "[扫描] connect %s -> %s"
                                % (ip, out.strip().replace("\n", " | "))))
                if "connected" in out or "already" in out:
                    found.append(ip)
            det = self.adb.devices_detail()
            self.log_q.put(("devinfo", ([d[0] for d in det],
                                        found[0] if found else "", "", det)))
        threading.Thread(target=work, daemon=True).start()
        self.root.after(200, self.drain)

    # ------------------------------------------------------------ 目标 App 识别
    def refresh_apps(self):
        if not self.check_ready(silent=True):
            return

        def work():
            out = self.adb.sh("pm list packages -3", timeout=30)
            pkgs = [l.split(":", 1)[1].strip() for l in out.splitlines()
                    if l.startswith("package:")]
            self.log_q.put(("apps", (sorted(pkgs), None)))
        threading.Thread(target=work, daemon=True).start()
        self.root.after(150, self.drain)

    def detect_foreground(self):
        if not self.check_ready(silent=True):
            return

        def work():
            p = ""
            for cmd in ("dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -3",
                        "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity' | head -3"):
                out = self.adb.sh(cmd, timeout=20)
                for l in out.splitlines():
                    m = re.search(r"([a-zA-Z][a-zA-Z0-9_.]*\.[a-zA-Z][a-zA-Z0-9_.]*)/[.a-zA-Z]", l)
                    if m:
                        p = m.group(1)
                        break
                    m2 = re.search(r"u0\s+([a-zA-Z][a-zA-Z0-9_.]*)/", l)
                    if m2:
                        p = m2.group(1)
                        break
                if p:
                    break
            self.log_q.put(("apps", ([p] if p else [], p or None)))
        threading.Thread(target=work, daemon=True).start()
        self.root.after(150, self.drain)

    def auto_detect_app(self):
        """优先级：① 日志里正在 PiP 的 App ② LSPosed 作用域（client hooked in）③ 当前前台"""
        if not self.check_ready(silent=True):
            return

        def work():
            lg = self.adb.sh("logcat -d | grep -i floatwin | tail -400", timeout=30)
            pip_pkg, scope = "", []
            for l in lg.splitlines():
                m = re.search(r"enterPip attempt#\d+ -> true\s*:\s*(\S+)", l)
                if m:
                    cls = m.group(1)
                    if "." in cls:
                        pip_pkg = cls.rsplit(".", 1)[0]
                m2 = re.search(r"client hooked in\s+(\S+)", l)
                if m2:
                    p = m2.group(1).strip()
                    if p and p not in scope:
                        scope.append(p)
            # 作用域里去掉明显的系统进程（那些是框架注入，不是用户勾的目标 App）
            cand = [p for p in scope if not p.startswith("com.android.")
                    and not p.startswith("android")
                    and p != "com.gpro27u.floatwin"]
            target = pip_pkg or (cand[0] if cand else "")
            self.log_q.put(("apps", (cand, target)))
            self.log_q.put(("text", "[识别] PiP中的=%s  作用域非系统项=%s"
                            % (pip_pkg or "(无)", ", ".join(cand) or "(无)")))
        threading.Thread(target=work, daemon=True).start()
        self.root.after(150, self.drain)

    def on_apps(self, pkgs, cur):
        if not pkgs:
            self.log_line("[App] 没读到列表，可手动填包名")
            return
        self.set_pkg_list(pkgs, cur)
        self.log_line("[App] 列表 %d 个，当前选中 %s" % (len(self.app_list), self.v_pkg.get()))
        if cur:
            self.status("已识别目标 App: %s" % cur, True)

    def pick_adb(self):
        p = filedialog.askopenfilename(title="选择 adb.exe", filetypes=[("adb.exe", "adb.exe"), ("所有", "*.*")])
        if p:
            self.adb_exe = p
            self.v_adb.set(p)
            self.adb = Adb(p, self.serial)

    def do_connect(self):
        self.adb_exe = self.v_adb.get().strip()
        self.ip = self.v_ip.get().strip()
        self.pkg = self.v_pkg.get().strip() or DEFAULT_PKG
        self.adb = Adb(self.adb_exe, self.serial)
        self.save_cfg()
        if not self.adb_exe or not os.path.isfile(self.adb_exe):
            self.status("adb.exe 路径无效", False)
            return
        if self.ip:
            out = self.adb.run("connect", "%s:5555" % self.ip)
            self.log_line("[connect] " + out.strip())
            if "connected" in out or "already" in out:
                self.set_serial("%s:5555" % self.ip)
        self.probe()

    def status(self, text, ok):
        self.v_status.set(text)
        self.l_status.configure(foreground="#2d7d46" if ok else "#c0392b")

    # ------------------------------------------------------------ 探测
    def auto_probe(self):
        if self.adb_exe and os.path.isfile(self.adb_exe):
            self.probe()

    def probe(self):
        def work():
            devs = self.adb.devices()
            ok = bool(devs)
            sw, sh = 1920, 1080
            ver = 0
            rect = None
            if ok:
                sz = self.adb.sh("wm size")
                m = re.search(r"(\d+)x(\d+)", sz)
                if m:
                    sw, sh = int(m.group(1)), int(m.group(2))
                    if sw < sh:
                        sw, sh = sh, sw
                d = self.adb.sh("dumpsys package %s" % PKG_MODULE)
                m = re.search(r"versionCode=(\d+)", d)
                if m:
                    ver = int(m.group(1))
                if ver >= 8:
                    rect = self.query_rect()
            self.log_q.put(("probe", (ok, devs, sw, sh, ver, rect)))
        threading.Thread(target=work, daemon=True).start()
        self.root.after(100, self.drain)

    def query_rect(self):
        """读电视上 PiP 窗口的真实位置和大小。
        主路：直接解析 dumpsys 里 pinned 任务的 bounds —— 不依赖任何守护线程/版本号；
        备路：老版 v8 守护线程打的 logcat（QUERY state）。"""
        pkg = self.v_pkg.get().strip() or DEFAULT_PKG
        out = self.adb.sh("dumpsys activity activities")
        lines = (out or "").splitlines()
        first_any = None
        for i, ln in enumerate(lines):
            if pkg not in ln or "Task{" not in ln:
                continue
            for j in range(i + 1, min(i + 8, len(lines))):
                m = re.search(r"bounds=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", lines[j])
                if not m:
                    continue
                x = int(m.group(1)); y = int(m.group(2))
                w = int(m.group(3)) - x
                h = int(m.group(4)) - y
                r = dict(x=x, y=y, w=w, h=h)
                if "mode=pinned" in ln:
                    return r                       # PiP 窗口，最准
                # 满屏的 bounds 不算"悬浮窗"，跳过
                if not (x == 0 and y == 0 and (w, h) == (self.screen_w, self.screen_h)) \
                        and first_any is None:
                    first_any = r
        if first_any:
            return first_any
        # 备路：v8 守护线程协议
        self.adb.sh("touch /data/local/tmp/floatwin_pip_query")
        time.sleep(0.8)
        lg = self.adb.sh("logcat -d | grep -i 'QUERY state' | tail -3")
        line = [l for l in lg.splitlines() if "QUERY state" in l]
        if not line:
            return None
        m = re.search(r"cur=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)", line[-1])
        if not m:
            return None
        return dict(x=int(m.group(1)), y=int(m.group(2)),
                    w=int(m.group(3)) - int(m.group(1)),
                    h=int(m.group(4)) - int(m.group(2)))

    def drain(self):
        try:
            while True:
                kind, payload = self.log_q.get_nowait()
                if kind == "probe":
                    self.on_probe(*payload)
                elif kind == "text":
                    self.log_text(payload)
                elif kind == "devinfo":
                    self.on_devinfo(*payload)
                elif kind == "apps":
                    self.on_apps(*payload)
                elif kind == "status":
                    self.v_status.set(payload[0])
                    self.l_status.configure(foreground="#2d7d46" if payload[1] else "#c0392b")
        except queue.Empty:
            pass
        self.root.after(200, self.drain)

    def on_probe(self, ok, devs, sw, sh, ver, rect):
        if not ok:
            self.status("未连接（点「连接」）", False)
            self.v_ver.set("模块: 未安装或设备未连接")
            self.v_drag.set("")
            return
        self.screen_w, self.screen_h = sw, sh
        self.status("已连接 %s" % (self.serial or ", ".join(devs)), True)
        self.mod_ver = ver
        self.v_ver.set("模块: %s" % ("未安装" if ver == 0 else "v%d 已安装" % ver))
        if ver >= 8:
            self.v_drag.set("拖动预览框即可；若电视上没跟着动，点「检测注入状态」看 SystemUI 是否生效")
        else:
            self.v_drag.set("模块没装好，到「安装维护」页安装")
        if not self.v_relay.get():
            self.v_relay.set("默认开启。遥控器方向键/确定键会转发进悬浮窗里的 App。")
        if rect and rect.get("w", 0) > 0 and rect.get("h", 0) > 0 \
                and rect["w"] * rect["h"] <= self.screen_w * self.screen_h * 0.9:
            # 合法悬浮窗 rect 才采纳；0x0 / 全屏 / 过渡期尺寸一律拒收，
            # 否则预览框会变成 0x0（红框消失没法拖）或 1920x1080
            self.win = rect
        self.draw()

    # ------------------------------------------------------------ Tab
    def build_tabs(self):
        # v33：整页放进可滚动容器 —— 窗口不够高时下面的按钮也能滚到
        self.sc = ScrollFrame(self.root)
        self.sc.pack(fill="both", expand=True, padx=6, pady=4)
        nb = ttk.Notebook(self.sc.inner)
        nb.pack(fill="both", expand=True)

        t1 = ttk.Frame(nb, padding=8)
        nb.add(t1, text="  窗口调整  ")
        self.build_tab_window(t1)

        t2 = ttk.Frame(nb, padding=8)
        nb.add(t2, text="  安装维护  ")
        self.build_tab_install(t2)

        t3 = ttk.Frame(nb, padding=8)
        nb.add(t3, text="  日志  ")
        self.build_tab_log(t3)

        t4 = ttk.Frame(nb, padding=8)
        nb.add(t4, text="  说明  ")
        self.build_tab_help(t4)

    # ------------------------------------------------ Tab1 窗口调整
    def build_tab_window(self, f):
        left = ttk.Frame(f)
        left.pack(side="left", fill="y")

        ttk.Label(left, text="屏幕预览：拖框内=移动，拖四角/四边=自由缩放",
                  font=("Microsoft YaHei", 9, "bold")).pack(anchor="w")
        self.pv_w, self.pv_h = 660, 371
        self.canvas = tk.Canvas(left, width=self.pv_w, height=self.pv_h,
                                bg="black", highlightthickness=1,
                                highlightbackground="#666")
        self.canvas.pack(pady=4)
        self.rect_id = self.canvas.create_rectangle(0, 0, 10, 10, outline="#e74c3c",
                                                    fill="#e74c3c", stipple="gray50", width=2)
        self.txt_id = self.canvas.create_text(5, 5, text="", fill="white",
                                              font=("Consolas", 9), anchor="nw")
        self.h_ids = {}
        for n in self.HANDLES:
            self.h_ids[n] = self.canvas.create_rectangle(0, 0, 8, 8, fill="#f1c40f",
                                                         outline="#7f6000", width=1)
        self.canvas.bind("<ButtonPress-1>", self.on_down)
        self.canvas.bind("<B1-Motion>", self.on_move)
        self.canvas.bind("<ButtonRelease-1>", self.on_up)
        self.canvas.bind("<Motion>", self.on_hover)

        self.v_xy = tk.StringVar(value="x=- y=-")
        ttk.Label(left, textvariable=self.v_xy, font=("Consolas", 10)).pack(anchor="w")

        opt = ttk.Frame(left)
        opt.pack(fill="x", pady=(2, 0))
        self.v_lock = tk.BooleanVar(value=True)
        ttk.Checkbutton(opt, text="缩放时锁定宽高比", variable=self.v_lock).pack(side="left")
        self.v_autorestart = tk.BooleanVar(value=True)
        ttk.Checkbutton(opt, text="缩放后自动重启App生效", variable=self.v_autorestart).pack(side="left", padx=8)

        g = ttk.LabelFrame(left, text="快捷位置", padding=4)
        g.pack(fill="x", pady=6)
        grid = ttk.Frame(g)
        grid.pack()
        for i, (txt, pos) in enumerate([("左上", "tl"), ("上中", "tc"), ("右上", "tr"),
                                        ("左中", "cl"), ("居中", "c"), ("右中", "cr"),
                                        ("左下", "bl"), ("下中", "bc"), ("右下", "br")]):
            ttk.Button(grid, text=txt, width=6,
                       command=lambda p=pos: self.set_quick_pos(p)).grid(row=i // 3, column=i % 3, padx=2, pady=2)

        # ---- 已安装应用（点一下就打开）----
        ap = ttk.LabelFrame(left, text="已安装应用（双击 = 设为目标并零黑屏启动）", padding=4)
        ap.pack(fill="both", expand=True, pady=6)

        af = ttk.Frame(ap)
        af.pack(fill="x")
        self.v_appfilter = tk.StringVar()
        e = ttk.Entry(af, textvariable=self.v_appfilter, width=20)
        e.pack(side="left", fill="x", expand=True)
        e.bind("<KeyRelease>", lambda ev: self.fill_app_list())
        ttk.Button(af, text="刷新", width=6, command=self.refresh_apps).pack(side="left", padx=3)
        self.v_appsys = tk.BooleanVar(value=False)
        ttk.Checkbutton(af, text="含系统", variable=self.v_appsys,
                        command=self.refresh_apps).pack(side="left")

        lw = ttk.Frame(ap)
        lw.pack(fill="both", expand=True, pady=(3, 0))
        self.app_list = tk.Listbox(lw, height=9, font=("Consolas", 9))
        sb = ttk.Scrollbar(lw, orient="vertical", command=self.app_list.yview)
        self.app_list.configure(yscrollcommand=sb.set)
        self.app_list.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        self.app_list.bind("<Double-Button-1>", lambda ev: self.app_launch_pinned())
        self.app_list.bind("<<ListboxSelect>>", lambda ev: self.on_app_select())

        ab = ttk.Frame(ap)
        ab.pack(fill="x", pady=(3, 0))
        ttk.Button(ab, text="零黑屏启动", style="Accent.TButton",
                   command=self.app_launch_pinned).pack(side="left")
        ttk.Button(ab, text="普通启动", command=self.app_launch_normal).pack(side="left", padx=3)
        ttk.Button(ab, text="设为目标", command=self.app_set_target).pack(side="left")
        ttk.Button(ab, text="强制停止", style="Danger.TButton",
                   command=lambda: self.force_stop_app(self.cur_app())).pack(side="left", padx=3)
        self.v_appinfo = tk.StringVar(value="列表为空时点「刷新」")
        ttk.Label(ap, textvariable=self.v_appinfo, foreground="#666",
                  wraplength=330).pack(anchor="w")

        self.apps = self.cfg.get("apps") or []      # [{"pkg":..,"cmp":..}]
        self.fill_app_list()

        # ---- 右侧参数 ----
        right = ttk.Frame(f)
        right.pack(side="left", fill="both", expand=True, padx=(14, 0))

        p = ttk.LabelFrame(right, text="预设（一键套用）", padding=6)
        p.pack(fill="x")
        self.v_preset = tk.StringVar(value="手机竖屏 phone")
        for name in PRESETS:
            ttk.Radiobutton(p, text=name, value=name, variable=self.v_preset,
                            command=self.on_preset).pack(anchor="w")
        self.v_preset_note = tk.StringVar(value="")
        ttk.Label(p, textvariable=self.v_preset_note, foreground="#666",
                  wraplength=320).pack(anchor="w", pady=(2, 0))

        s = ttk.LabelFrame(right, text="细项（留空 = 不写入该参数）", padding=6)
        s.pack(fill="x", pady=8)

        self.v_size = tk.StringVar(value="")
        self.mk_entry(s, 0, "窗口像素 宽x高:", self.v_size, "例 547x972 / 851x479")
        self.v_scale = tk.StringVar(value="150")
        self.mk_entry(s, 1, "放大 %:", self.v_scale, "100-300，预设为空时生效")
        self.v_dp = tk.StringVar(value="411")
        self.mk_entry(s, 2, "逻辑宽度 dp:", self.v_dp, "TV 应用 960；手机 App 411")
        self.v_sw = tk.StringVar(value="411")
        self.mk_entry(s, 3, "sw (资源桶):", self.v_sw, "决定用手机布局还是电视布局")
        self.v_orient = tk.StringVar(value="1")
        self.mk_entry(s, 4, "方向 0/1/2:", self.v_orient, "0 自动  1 竖屏  2 横屏")
        self.v_pos = tk.StringVar(value="tr")
        self.mk_entry(s, 5, "位置:", self.v_pos, "tl/tr/bl/br/c，或 300,200（实时）")

        b = ttk.Frame(right)
        b.pack(fill="x", pady=8)
        ttk.Button(b, text="应用到电视并重启App", style="Accent.TButton",
                   command=self.apply_all).pack(fill="x", pady=3)
        ttk.Button(b, text="只写参数（不重启）", command=self.write_params).pack(fill="x", pady=3)
        ttk.Button(b, text="仅重启目标App", command=self.restart_app).pack(fill="x", pady=3)
        self.v_pinned = tk.IntVar(value=1)
        ttk.Checkbutton(b, text="零黑屏启动（直接以 PiP 窗口拉起，不经过全屏）",
                        variable=self.v_pinned).pack(anchor="w", pady=(2, 0))
        ttk.Button(b, text="零黑屏启动（直接进 PiP）", style="Accent.TButton",
                   command=self.launch_pinned).pack(fill="x", pady=3)
        ttk.Button(b, text="修复 SELinux（让电视读得到参数）",
                   command=self.fix_selinux).pack(fill="x", pady=3)
        ttk.Button(b, text="查询电视上的实际窗口", command=self.do_query).pack(fill="x", pady=3)
        ttk.Button(b, text="检测注入状态（SystemUI 是否生效）",
                   command=self.check_inject).pack(fill="x", pady=3)
        ttk.Button(b, text="恢复出厂默认（清参数）", command=self.clear_params).pack(fill="x", pady=3)

        r = ttk.LabelFrame(right, text="遥控中转（方向键/确定键转发进悬浮窗）", padding=6)
        r.pack(fill="x", pady=4)
        rb = ttk.Frame(r)
        rb.pack(fill="x")
        ttk.Button(rb, text="中转：开", width=10, command=lambda: self.set_relay("1")).pack(side="left", padx=2)
        ttk.Button(rb, text="中转：关", width=10, command=lambda: self.set_relay("0")).pack(side="left", padx=2)
        self.v_relay = tk.StringVar(value="")
        ttk.Label(r, textvariable=self.v_relay, foreground="#555", wraplength=330,
                  justify="left").pack(anchor="w", pady=(4, 0))

        # ---------- 返回键行为 ----------
        r = ttk.LabelFrame(right, text="遥控器「返回」键怎么退场", padding=6)
        r.pack(fill="x", pady=4)
        ttk.Label(r, text="推荐「电脑接管」：本程序常驻监听，一按返回就用 adb 执行 "
                          "am force-stop —— 和上面「强制停止」同一条命令，实测不黑屏、不回桌面。",
                  foreground="#555", wraplength=330, justify="left").pack(anchor="w")
        rw = ttk.Frame(r)
        rw.pack(fill="x", pady=(4, 0))
        ttk.Button(rw, text="电视端守护：装/启", width=15, style="Accent.TButton",
                   command=self.daemon_install).pack(side="left", padx=2)
        ttk.Button(rw, text="停守护", width=8, command=self.daemon_stop).pack(side="left", padx=2)
        ttk.Button(rw, text="查状态", width=8, command=self.daemon_status).pack(side="left", padx=2)
        ttk.Label(r, text="守护装在电视上，装一次管到电视重启，电脑关了也照常收返回键。",
                  foreground="#555", wraplength=330, justify="left").pack(anchor="w")
        rw2 = ttk.Frame(r)
        rw2.pack(fill="x", pady=(4, 0))
        ttk.Button(rw2, text="电脑接管：开", width=15,
                   command=self.start_back_watch).pack(side="left", padx=2)
        ttk.Button(rw2, text="电脑接管：关", width=15,
                   command=self.stop_back_watch).pack(side="left", padx=2)
        ttk.Label(r, text="电脑接管＝控制台常驻监听（守护没装时的备用方案，关掉控制台就失效）。",
                  foreground="#888", wraplength=330, justify="left").pack(anchor="w")
        ttk.Separator(r).pack(fill="x", pady=6)
        ttk.Label(r, text="微调（一般不用动）：", foreground="#555").pack(anchor="w")
        rf = ttk.Frame(r)
        rf.pack(fill="x", pady=(4, 0))
        ttk.Button(rf, text="挪 HDMI 前台：开", width=16,
                   command=lambda: self.set_back_flag("front", "1")).pack(side="left", padx=2)
        ttk.Button(rf, text="不挪 HDMI", width=12,
                   command=lambda: self.set_back_flag("front", "0")).pack(side="left", padx=2)
        rf2 = ttk.Frame(r)
        rf2.pack(fill="x", pady=(4, 0))
        ttk.Button(rf2, text="直接收掉（默认）", width=16,
                   command=lambda: self.set_back_flag("hide", "0")).pack(side="left", padx=2)
        ttk.Button(rf2, text="先透明再收", width=12,
                   command=lambda: self.set_back_flag("hide", "1")).pack(side="left", padx=2)
        self.v_back = tk.StringVar(value="")
        ttk.Label(r, textvariable=self.v_back, foreground="#2F6BFF", wraplength=330,
                  justify="left").pack(anchor="w", pady=(4, 0))

        # ---------- 前台与显示 ----------
        b = ttk.LabelFrame(right, text="前台与显示", padding=6)
        b.pack(fill="x", pady=4)
        b1 = ttk.Frame(b)
        b1.pack(fill="x")
        ttk.Button(b1, text="前台还 HDMI：开", width=15,
                   command=lambda: self.set_home("1")).pack(side="left", padx=2)
        ttk.Button(b1, text="：关", width=6,
                   command=lambda: self.set_home("0")).pack(side="left", padx=2)
        ttk.Button(b1, text="隐形启动：开", width=13,
                   command=lambda: self.set_ghost("1")).pack(side="left", padx=2)
        ttk.Button(b1, text="：关", width=6,
                   command=lambda: self.set_ghost("0")).pack(side="left", padx=2)
        self.v_black = tk.StringVar(value="")
        ttk.Label(b, textvariable=self.v_black, foreground="#555", wraplength=330,
                  justify="left").pack(anchor="w", pady=(4, 0))
        b2 = ttk.Frame(b)
        b2.pack(fill="x", pady=(4, 0))
        ttk.Button(b2, text="重载 SystemUI（修复拖动/缩放失效）",
                   command=self.reload_systemui).pack(fill="x")
        ttk.Label(b, text="拖动／缩放偶发失效时点它。会黑屏 1~2 秒并短暂中断 HDMI 画面，"
                          "不重启电视。",
                  foreground="#888", wraplength=330, justify="left").pack(anchor="w")

        self.on_preset()

    def set_relay(self, v):
        if not self.check_ready(silent=True):
            return
        if v == "1":
            self.adb.sh("rm -f /data/local/tmp/floatwin_key_relay")
        else:
            self.adb.sh("echo 0 > /data/local/tmp/floatwin_key_relay")
        self.v_relay.set("遥控中转：%s。重启电视后生效；遥控器方向键/确定键会转发进悬浮窗里的 App。"
                         % ("开（默认）" if v == "1" else "关"))
        self.log_line("[遥控] 中转 -> %s" % ("开" if v == "1" else "关"))

    # ---------------- 返回键接管（常驻监听 logcat → 强制停止） ----------------
    def start_back_watch(self):
        if self.back_watch:
            self.log_line("[返回键] 接管已经在跑")
            return
        self.back_watch = True
        self.cfg["back_watch"] = True
        self.save_cfg()
        threading.Thread(target=self._back_watch_loop, daemon=True).start()
        self.log_line("[返回键] 接管已开：悬浮窗里按返回 → 电脑立刻 am force-stop"
                      "（和本页「强制停止」同一条命令，实测不黑屏、不回桌面）")

    def stop_back_watch(self):
        self.back_watch = False
        self.cfg["back_watch"] = False
        self.save_cfg()
        try:
            if self.back_watch_proc:
                self.back_watch_proc.kill()
        except Exception:
            pass
        self.log_line("[返回键] 接管已关（退回 App 自己收场）")

    # ---------------- 电视端返回键守护（不依赖电脑常开）----------------
    DAEMON = "/data/local/tmp/floatwin_backd.sh"

    def daemon_install(self, auto=False):
        """把守护脚本推进电视并启动：电视重启前一直有效，电脑关了也照样收返回键"""
        if not self.check_ready(silent=True):
            return
        if self.daemon_status(silent=True):
            if not auto:
                self.log_line("[守护] 已经在跑，不用重复装")
            return
        src = os.path.abspath(os.path.join(
            os.path.dirname(os.path.abspath(__file__)), "..", "tools", "floatwin_backd.sh"))
        if not os.path.exists(src):
            self.log_line("[守护] 找不到脚本：%s" % src)
            return
        threading.Thread(target=self._daemon_install_async, args=(src,), daemon=True).start()

    def _daemon_install_async(self, src):
        try:
            argv = [self.adb_exe]
            if self.serial:
                argv += ["-s", self.serial]
            subprocess.run(argv + ["push", src, self.DAEMON],
                           capture_output=True, timeout=30, creationflags=NO_WINDOW)
            self.adb.sh("chmod 0755 %s" % self.DAEMON)
            self.adb.sh("setsid sh %s >/dev/null 2>&1 < /dev/null &" % self.DAEMON, timeout=15)
            time.sleep(1.5)
            self.daemon_status()
        except Exception as e:
            self.log_q.put(("text", "[守护] 安装失败：%s" % e))

    def daemon_stop(self):
        if not self.check_ready(silent=True):
            return
        self.adb.sh("pkill -f floatwin_backd", timeout=10)
        self.log_line("[守护] 已停止（返回键退回 App 自己收场）")

    def daemon_status(self, silent=False):
        out = self.adb.sh("ps -ef | grep floatwin_backd | grep -v grep", timeout=10)
        alive = "floatwin_backd" in (out or "")
        if not silent:
            self.log_line("[守护] 电视端返回键守护：%s" % ("运行中 ✓" if alive else "未运行"))
        return alive

    def reload_systemui(self):
        """拖动/缩放间歇失效的修复手段：让模块在 SystemUI 里重新加载一次"""
        if not messagebox.askyesno(
                "确认：重载 SystemUI",
                "作用：拖动／缩放间歇失效时，用它让模块在 SystemUI 里重新生效。\n\n"
                "影响：电视画面会黑 1~2 秒、状态栏和桌面重绘，HDMI 画面可能短暂中断。\n"
                "不会重启电视，但会打断当前观看。\n\n"
                "如果 10 秒后画面没回来，请长按遥控器电源键重启电视。\n\n确定继续？"):
            return
        if not self.check_ready(silent=True):
            return
        self.log_line("[SystemUI] 正在重载…（画面会黑 1~2 秒）")
        out = self.adb.sh("kill $(pgrep -f com.android.systemui)", timeout=15)
        self.log_line("[SystemUI] 已发送重载：%s" % (out.strip() or "ok"))
        self.root.after(8000, lambda: self.log_line(
            "[SystemUI] 重载完成，试一下拖动／缩放是否恢复"))

    def _back_watch_loop(self):
        while self.back_watch:
            try:
                argv = [self.adb_exe]
                if self.serial:
                    argv += ["-s", self.serial]
                # -T 1：只从"最后 1 条"开始跟，避免启动时把历史日志倒出来误触发
                argv += ["logcat", "-v", "time", "-s", "FloatWin:I", "-T", "1"]
                self.back_watch_proc = subprocess.Popen(
                    argv, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                    creationflags=NO_WINDOW)
                for raw in iter(self.back_watch_proc.stdout.readline, b""):
                    if not self.back_watch:
                        break
                    if b"BACKKEY" not in raw:
                        continue
                    line = raw.decode("utf-8", "replace")
                    pkg = line.split("BACKKEY", 1)[1].strip()
                    if not pkg:
                        pkg = self.pkg
                    self.log_q.put(("text", "[返回键] 收到 %s → am force-stop" % pkg))
                    self.adb.sh("am force-stop %s" % pkg)
                    self.log_q.put(("text", "[返回键] 已强制停止 %s" % pkg))
                try:
                    self.back_watch_proc.wait(timeout=3)
                except Exception:
                    pass
            except Exception as e:
                self.log_q.put(("text", "[返回键] 监听异常：%s（2 秒后重连）" % e))
            if self.back_watch:
                time.sleep(2)

    def set_back_flag(self, which, v):
        if not self.check_ready(silent=True):
            return
        f = "/data/local/tmp/floatwin_back_%s" % which
        if v == "1":
            self.adb.sh("echo 1 > %s" % f)
        else:
            self.adb.sh("echo 0 > %s" % f)
        name = "挪 HDMI 前台" if which == "front" else "退出前先透明"
        self.v_back.set("%s：%s（下次打开悬浮窗即生效，不用重启电视）"
                        % (name, "开" if v == "1" else "关"))
        self.log_line("[返回键] %s -> %s" % (name, "开" if v == "1" else "关"))

    # ---------------- 前台与显示 ----------------

    def set_home(self, v):
        if not self.check_ready(silent=True):
            return
        if v == "1":
            self.adb.sh("rm -f /data/local/tmp/floatwin_home_restore")
        else:
            self.adb.sh("echo 0 > /data/local/tmp/floatwin_home_restore")
        self.v_black.set("前台还 HDMI：%s。%s" % (
            "开（默认）" if v == "1" else "关",
            "进 PiP 后自动把 HDMI 直通源拉回前台，前台不再经过小米桌面，Windows 就不会黑屏重同步。"
            if v == "1" else "恢复原行为（前台退回桌面）。"))
        self.log_line("[前台] 前台还 HDMI -> %s" % ("开" if v == "1" else "关"))

    def set_ghost(self, v):
        if not self.check_ready(silent=True):
            return
        if v == "1":
            self.adb.sh("echo 1 > /data/local/tmp/floatwin_ghost")
        else:
            self.adb.sh("rm -f /data/local/tmp/floatwin_ghost")
        self.log_line("[显示] 隐形启动 -> %s（%s）" % (
            "开" if v == "1" else "关（默认）",
            "把 App 首屏窗口调透明盖住黑底，带 4 秒无条件恢复安全阀" if v == "1" else "安全"))

    def check_inject(self):
        """检测 LSPosed 到底把模块注入进了哪些进程 —— 实时拖动/放大都依赖 SystemUI 那份"""
        if not self.check_ready(silent=True):
            return

        def work():
            self.log_q.put(("text", "===== 注入状态检测 ====="))
            ss = self.adb.sh("logcat -d | grep -aE 'SAFEOPT OK|KEYHOOK OK|TARGEREG OK' | tail -3")
            ok_ss = ("SAFEOPT OK" in ss) or ("KEYHOOK OK" in ss) or ("TARGEREG OK" in ss)
            self.log_q.put(("text", "系统框架 system_server : %s"
                            % ("已注入" if ok_ss else "未注入（需重启电视）")))

            su = self.adb.sh("logcat -d | grep -a 'PIPCLASS' | grep -a 'systemui' | tail -3")
            ok_su = "systemui" in (su or "")
            self.log_q.put(("text", "SystemUI（实时拖动靠它）: %s"
                            % ("已注入" if ok_su else
                               "未注入 —— 在 LSPosed 里勾选「SystemUI / 系统界面」后重启电视")))
            if not ok_su:
                self.log_q.put(("text", "   没注入 SystemUI 时：窗口放大、实时拖动都不生效，"
                                        "改位置只能靠「应用」重启 App"))

            ap = self.adb.sh("logcat -d | grep -a 'client hooked in' | tail -8")
            pkgs = []
            for ln in (ap or "").splitlines():
                i = ln.find("client hooked in ")
                if i >= 0:
                    p = ln[i + len("client hooked in "):].strip()
                    if p and p not in pkgs:
                        pkgs.append(p)
            self.log_q.put(("text", "已注入的 App 进程: %s"
                            % ("、".join(pkgs) if pkgs else
                               "无（日志可能已被清，先启动一次悬浮窗再点这个按钮）")))
            self.log_q.put(("text", "提示：logcat 缓冲一旦被清就看不全，"
                                    "最准的做法是重启电视后立刻点一次。"))

        threading.Thread(target=work, daemon=True).start()
        self.root.after(150, self.drain)

    def mk_entry(self, parent, row, label, var, hint):
        ttk.Label(parent, text=label, width=16, anchor="w").grid(row=row, column=0, sticky="w", pady=2)
        ttk.Entry(parent, textvariable=var, width=16).grid(row=row, column=1, sticky="w", pady=2)
        ttk.Label(parent, text=hint, foreground="#888").grid(row=row, column=2, sticky="w", padx=6)

    def on_preset(self):
        name = self.v_preset.get()
        d = PRESETS.get(name, {})
        self.v_preset_note.set(d.get("note", ""))
        self.v_preset_val = d.get("preset", "tv")
        if not self.v_size.get() or self.v_size.get() in ("547x972", "851x479", "400x711", "567x319"):
            self.v_size.set(d.get("size", ""))
        self.v_dp.set(d.get("dp", ""))
        self.v_sw.set(d.get("sw", ""))
        self.v_orient.set(d.get("orient", ""))
        self.apply_preview_size(d.get("size", ""))

    def apply_preview_size(self, size):
        m = re.match(r"^\s*(\d+)\s*[xX]\s*(\d+)\s*$", size or "")
        if m:
            self.win["w"] = int(m.group(1))
            self.win["h"] = int(m.group(2))
        else:
            if self.v_preset_val == "phone":
                self.win["h"] = int(round(self.screen_h * 0.9))
                self.win["w"] = int(round(self.win["h"] * 9.0 / 16.0))
            else:
                self.win["w"] = 851
                self.win["h"] = 479
        self.clamp_win()
        self.draw()

    def clamp_win(self):
        self.win["x"] = max(0, min(self.win["x"], self.screen_w - self.win["w"]))
        self.win["y"] = max(0, min(self.win["y"], self.screen_h - self.win["h"]))

    # ------------------------------------------------ 预览绘制 / 拖动
    @property
    def k(self):
        return min(self.pv_w / self.screen_w, self.pv_h / self.screen_h)

    # ------------------------------------------------ 预览绘制 / 拖动 / 自由缩放
    HANDLES = ("nw", "n", "ne", "e", "se", "s", "sw", "w")
    CURSORS = {"nw": "size_nw_se", "se": "size_nw_se", "n": "size_ns", "s": "size_ns",
               "ne": "size_ne_sw", "sw": "size_ne_sw", "e": "size_we", "w": "size_we"}

    def box_px(self):
        """当前窗口在 canvas 里的像素矩形"""
        k = self.k
        ox = (self.pv_w - self.screen_w * k) / 2
        oy = (self.pv_h - self.screen_h * k) / 2
        return (ox + self.win["x"] * k, oy + self.win["y"] * k,
                self.win["w"] * k, self.win["h"] * k, ox, oy)

    def draw(self):
        # v21 兜底：win 被写坏成 0x0（例如查询到退场中的空 bounds）时，
        # 恢复成一个可用的小窗，保证红框永远存在、拖动缩放不会被锁死
        if self.win.get("w", 0) <= 10 or self.win.get("h", 0) <= 10:
            self.win = dict(x=1362, y=12, w=547, h=972)
        bx, by, bw, bh, ox, oy = self.box_px()
        self.canvas.coords(self.rect_id, bx, by, bx + bw, by + bh)
        self.canvas.coords(self.txt_id, bx + 5, by + 4)
        self.canvas.itemconfig(self.txt_id, text="%dx%d" % (self.win["w"], self.win["h"]))
        for name in self.HANDLES:
            hx = bx + (0 if "w" in name else (bw / 2 if "n" in name or "s" in name else bw))
            hy = by + (0 if "n" in name else (bh / 2 if "w" in name or "e" in name else bh))
            self.canvas.coords(self.h_ids[name], hx - 4, hy - 4, hx + 4, hy + 4)
        self.v_xy.set("x=%d  y=%d   窗口 %dx%d   屏幕 %dx%d"
                      % (self.win["x"], self.win["y"], self.win["w"], self.win["h"],
                         self.screen_w, self.screen_h))

    def hit_handle(self, e):
        bx, by, bw, bh, ox, oy = self.box_px()
        near_l = abs(e.x - bx) <= 7
        near_r = abs(e.x - (bx + bw)) <= 7
        near_t = abs(e.y - by) <= 7
        near_b = abs(e.y - (by + bh)) <= 7
        inside = bx - 7 <= e.x <= bx + bw + 7 and by - 7 <= e.y <= by + bh + 7
        if not inside:
            return None
        v = ("n" if near_t else "s" if near_b else "")
        h = ("w" if near_l else "e" if near_r else "")
        if v and h:
            return v + h
        if v:
            return v
        if h:
            return h
        return "move"

    def on_hover(self, e):
        if getattr(self, "dragging", False):
            return
        m = self.hit_handle(e)
        self.canvas.configure(cursor=self.CURSORS.get(m, "arrow") if m else "arrow")

    def on_down(self, e):
        m = self.hit_handle(e)
        if m is None:
            return
        self.mode = m
        self.dragging = True
        self.drag_start = (e.x, e.y)
        self.drag_rect = (self.win["x"], self.win["y"], self.win["w"], self.win["h"])
        self.lock_ratio = float(self.win["w"]) / max(1.0, float(self.win["h"]))

    def on_move(self, e):
        if not getattr(self, "dragging", False):
            return
        k = self.k
        dx = (e.x - self.drag_start[0]) / k
        dy = (e.y - self.drag_start[1]) / k
        x0, y0, w0, h0 = self.drag_rect
        x, y, w, h = x0, y0, w0, h0
        m = getattr(self, "mode", "move")

        if m == "move":
            x, y = x0 + dx, y0 + dy
        else:
            if "w" in m:
                x, w = x0 + dx, w0 - dx
            if "e" in m:
                w = w0 + dx
            if "n" in m:
                y, h = y0 + dy, h0 - dy
            if "s" in m:
                h = h0 + dy
            if w < 80:
                if "w" in m:
                    x = x0 + (w0 - 80)
                w = 80
            if h < 80:
                if "n" in m:
                    y = y0 + (h0 - 80)
                h = 80
            if self.v_lock.get():
                r = self.lock_ratio
                if m in ("nw", "ne", "se", "sw"):
                    h = w / r
                    if "n" in m:
                        y = y0 + (h0 - h)
                elif m in ("e", "w"):
                    h = w / r
                    y = y0 + (h0 - h) / 2.0
                else:
                    w = h * r
                    x = x0 + (w0 - w) / 2.0

        self.win["x"], self.win["y"] = int(round(x)), int(round(y))
        self.win["w"], self.win["h"] = int(round(w)), int(round(h))
        # 不允许超出屏幕
        if self.win["w"] > self.screen_w:
            self.win["w"] = self.screen_w
        if self.win["h"] > self.screen_h:
            self.win["h"] = self.screen_h
        self.clamp_win()
        self.draw()
        # v18：尺寸一起下发，模块端按 x,y,w,h 做实时缩放
        self.drag_latest = (self.win["x"], self.win["y"], self.win["w"], self.win["h"])
        # 坐标同步进参数框：实时拖动没生效时，也能靠「应用」重启 App 生效
        self.v_pos.set("%d,%d" % (self.win["x"], self.win["y"]))
        if m != "move":
            self.v_size.set("%dx%d" % (self.win["w"], self.win["h"]))

    def on_up(self, e):
        self.dragging = False
        self.push_live(force=True)
        if getattr(self, "mode", "move") != "move" and self.v_autorestart.get():
            # v18：模块支持实时缩放时不用重启 App（尺寸已经实时下发了）
            if self.live_resize_ok():
                self.v_status.set("尺寸已实时下发（无需重启 App）")
                self.l_status.configure(foreground="#2d7d46")
            else:
                self.root.after(150, self.apply_all)

    def live_resize_ok(self):
        """模块版本 >= 18 才支持实时缩放（结果缓存，只探测一次）"""
        if getattr(self, "_live_resize", None) is not None:
            return self._live_resize
        if self.mod_ver < 18:
            self.mod_ver = self.refresh_mod_ver()   # 只在松手时同步探测一次，不会卡界面
        self._live_resize = (self.mod_ver >= 18)
        return self._live_resize

    def tick_pusher(self):
        if self.drag_latest is not None:
            self.push_live()
        self.root.after(180, self.tick_pusher)

    def push_live(self, force=False):
        """实时拖动下发：把坐标写进 /data/local/tmp/floatwin_pip_pos，
        SystemUI 里的守护线程每 250ms 读一次并移动窗口。
        所以前提是 LSPosed 也注入了 SystemUI —— 没注入就只能靠重启 App 生效。

        v17 修正：
        ① 原来这里会同步调用 refresh_mod_ver()（一次 adb shell dumpsys），
           拖动时 Tk 主线程被它堵住，表现就是「控制台未响应」；现在只记坐标，不发命令。
        ② 原来每次下发都新起一个线程跑 adb（拖动时每秒几十个进程堆积），
           改成一条常驻的发送线程 + 只保留最新坐标，天然合并。"""
        xy = self.drag_latest
        if xy is None:
            return
        self.drag_latest = None
        self.last_sent = xy
        self._pending_pos = xy
        if not getattr(self, "_sender_alive", False):
            self._sender_alive = True
            self._sender_idle = 0
            threading.Thread(target=self._pos_sender, daemon=True).start()

    def _pos_sender(self):
        """常驻发送线程：每 250ms 只把最新坐标写一次（与守护线程的轮询节奏对齐）"""
        while True:
            time.sleep(0.25)
            xy = self._pending_pos
            if xy is None:
                self._sender_idle += 1
                if self._sender_idle > 12:      # 空闲约 3 秒就收工，别常驻占资源
                    self._sender_alive = False
                    return
                continue
            self._sender_idle = 0
            self._pending_pos = None
            try:
                # v18：x,y,w,h —— 带尺寸就是实时缩放，只有两个值时模块仍按"只挪位置"处理
                cmd = "echo %d,%d > /data/local/tmp/floatwin_pip_pos" % xy[:2]
                if len(xy) >= 4:
                    cmd = "echo %d,%d,%d,%d > /data/local/tmp/floatwin_pip_pos" % xy
                self.adb.sh(cmd, timeout=5)
            except Exception:
                pass

    def set_quick_pos(self, p):
        w, h = self.win["w"], self.win["h"]
        W, H = self.screen_w, self.screen_h
        pos = {"tl": (0, 0), "tc": ((W - w) // 2, 0), "tr": (W - w, 0),
               "cl": (0, (H - h) // 2), "c": ((W - w) // 2, (H - h) // 2),
               "cr": (W - w, (H - h) // 2),
               "bl": (0, H - h), "bc": ((W - w) // 2, H - h), "br": (W - w, H - h)}[p]
        self.win["x"], self.win["y"] = pos
        self.clamp_win()
        self.draw()
        self.v_pos.set(p)
        self.drag_latest = (self.win["x"], self.win["y"])
        self.push_live(force=True)

    # ------------------------------------------------ 参数下发
    def write_params(self):
        cmds = []
        cmds.append("echo %s > /data/local/tmp/floatwin_pip_preset" % self.v_preset_val)
        for key, var in (("floatwin_pip_size", self.v_size),
                         ("floatwin_pip_scale", self.v_scale),
                         ("floatwin_target_dp", self.v_dp),
                         ("floatwin_pip_sw", self.v_sw),
                         ("floatwin_pip_orient", self.v_orient)):
            v = var.get().strip()
            if v:
                cmds.append("echo %s > /data/local/tmp/%s" % (v, key))
        # v19：位置必须写成 4 段 x,y,w,h。
        # 之前这里写的是参数框里的 "266,0" 两段 —— 模块端收到两段就只平移不缩放，
        # 结果「点一下应用/写参数」之后实时缩放就失效了（用户反馈缩放不行的真正原因）。
        # 四段同时满足两个用途：启动时按 x,y 定位 + 悬浮时实时按 w,h 缩放。
        pos_val = self.v_pos.get().strip()
        if pos_val:
            if re.match(r"^\d+\s*,\s*\d+$", pos_val):
                pos_val = "%d,%d,%d,%d" % (self.win["x"], self.win["y"],
                                           self.win["w"], self.win["h"])
            cmds.append("echo %s > /data/local/tmp/floatwin_pip_pos" % pos_val)
        cmd = "; ".join(cmds)
        out = self.adb.sh(cmd)
        self.log_line("[写参数] " + cmd)
        if out.strip():
            self.log_line("  " + out.strip())
        self.status("参数已写入", True)
        return True

    def restart_app(self):
        pkg = self.v_pkg.get().strip() or DEFAULT_PKG
        if getattr(self, "v_pinned", None) and self.v_pinned.get():
            self.launch_pinned(stop_first=True)
            return
        self.adb.sh("am force-stop %s" % pkg)
        time.sleep(0.4)
        out = self.adb.sh("monkey -p %s 1" % pkg)
        self.log_line("[重启App] %s -> %s" % (pkg, out.strip().replace("\n", " | ")))

    # ---------- v13：零黑屏启动 ----------
    def resolve_launcher(self, pkg):
        """解析 App 的 LAUNCHER Activity，返回 pkg/.Cls"""
        out = self.adb.sh("cmd package resolve-activity --brief --user 0 "
                          "-a android.intent.action.MAIN "
                          "-c android.intent.category.LAUNCHER %s" % pkg)
        for line in reversed((out or "").splitlines()):
            line = line.strip()
            if "/" in line and line.startswith(pkg):
                return line
        return ""

    # ------------------------------------------------ 已安装应用
    def refresh_apps(self):
        """读电视上的可启动应用（有 LAUNCHER Activity 的才算），结果存进配置文件，
        下次打开 GUI 直接就有，不用再连一次电视。"""
        def work():
            flag = "" if self.v_appsys.get() else "-3"
            out = self.adb.sh("pm list packages %s" % flag)
            pkgs = []
            for ln in (out or "").splitlines():
                ln = ln.strip()
                if ln.startswith("package:"):
                    pkgs.append(ln[8:])
            pkgs.sort()
            res = []
            for p in pkgs:
                c = self.resolve_launcher(p)
                if c:
                    res.append({"pkg": p, "cmp": c})
            self.apps = res
            self.cfg["apps"] = res
            self.save_cfg()
            self.root.after(0, self.fill_app_list)
            self.log_line("[应用列表] 刷新完成：共 %d 个可启动应用" % len(res))
        self.status("正在读取电视上的应用列表…", None)
        threading.Thread(target=work, daemon=True).start()

    def fill_app_list(self):
        if not hasattr(self, "app_list"):
            return
        kw = (self.v_appfilter.get() or "").strip().lower()
        self.app_list.delete(0, "end")
        for it in (getattr(self, "apps", None) or []):
            p = it.get("pkg", "")
            if kw and kw not in p.lower():
                continue
            self.app_list.insert("end", p)
        n = self.app_list.size()
        self.v_appinfo.set(("匹配 %d 个（共 %d）" % (n, len(self.apps or [])))
                           if kw else ("共 %d 个应用，双击直接启动" % n))

    def cur_app(self):
        try:
            sel = self.app_list.curselection()
            if not sel:
                return ""
            return self.app_list.get(sel[0]).strip()
        except Exception:
            return ""

    def on_app_select(self):
        p = self.cur_app()
        if p:
            self.v_appinfo.set("选中：%s" % p)

    def _use_app_as_target(self):
        p = self.cur_app()
        if not p:
            self.status("先在列表里选一个应用", False)
            return ""
        self.v_pkg.set(p)
        self.pkg = p
        self.save_cfg()
        return p

    def app_set_target(self):
        p = self._use_app_as_target()
        if p:
            self.log_line("[目标App] 已设为 %s" % p)
            self.status("目标 App 已切换为 %s" % p, True)

    def app_launch_pinned(self):
        if self._use_app_as_target():
            self.launch_pinned()

    def app_launch_normal(self):
        if self._use_app_as_target():
            self.restart_app()

    def launch_pinned(self, stop_first=True):
        """零黑屏启动：直接把 App 拉起到 PINNED(PiP) 窗口模式。
        普通启动必然先全屏（HDMI 前台 Activity 被 pause → PC 黑一次），
        再进 PiP 又退给桌面（再黑），最后把 HDMI 拉回前台（再黑）。
        直接以 PiP 拉起则全程不碰 HDMI 前台，理论上一次都不黑。"""
        pkg = self.v_pkg.get().strip() or DEFAULT_PKG
        if stop_first:
            self.adb.sh("am force-stop %s" % pkg)
            time.sleep(0.4)
        cmp = self.resolve_launcher(pkg)
        if not cmp:
            self.log_line("[零黑屏启动] 解析不到启动 Activity，回退普通启动")
            out = self.adb.sh("monkey -p %s 1" % pkg)
            self.log_line("  [回退] %s" % out.strip().replace("\n", " | "))
            return
        out = self.adb.sh("am start-activity --windowingMode 2 -n %s" % cmp)
        flat = out.strip().replace("\n", " | ")
        self.log_line("[零黑屏启动] %s -> %s" % (cmp, flat))
        # 注意：am 失败时只打印 "Exception occurred ..." / "SecurityException"，
        # 不含 "rror"，所以必须一并判断，否则会误报成功
        bad = [k for k in ("rror", "Exception", "Denial", "denied", "not found")
               if k in out]
        if bad:
            self.log_line("  启动失败（命中关键字 %s），回退普通启动（会黑）" % "/".join(bad))
            for ln in out.splitlines():
                if "Exception" in ln or "Denial" in ln:
                    self.log_line("    " + ln.strip()[:200])
            out2 = self.adb.sh("monkey -p %s 1" % pkg)
            self.log_line("  [回退] %s" % out2.strip().replace("\n", " | "))
        else:
            self.status("已以 PiP 窗口直接拉起", True)
            self.root.after(1500, self.do_query)

    def fix_selinux(self):
        """system_server / SystemUI / App 读 /data/local/tmp/floatwin_* 会被 SELinux 拒，
        表现是 GUI 写的参数全部静默失效（回退默认）。这里热补放行，重启后失效。"""
        rules = [
            "allow system_server shell_data_file file { getattr open read }",
            "allow system_server shell_data_file dir { search getattr open read }",
            "allow platform_app shell_data_file file { getattr open read }",
            "allow platform_app shell_data_file dir { search getattr open read }",
            "allow system_app shell_data_file file { getattr open read }",
            "allow priv_app shell_data_file file { getattr open read }",
            "allow untrusted_app_all shell_data_file file { getattr open read }",
            "allow untrusted_app_all shell_data_file dir { search getattr open read }",
        ]
        inner = " ".join('"%s"' % r for r in rules)
        out = self.adb.sh("su -c '/data/adb/magisk/magiskpolicy --live %s'" % inner, timeout=40)
        flat = out.strip().replace("\n", " | ")
        self.log_line("[SELinux] " + (flat or "已放行（无输出=成功）"))
        chk = self.adb.sh("su -c '/data/adb/magisk/magiskpolicy --print-rules' 2>/dev/null "
                          "| grep shell_data_file | grep -c system_server", timeout=40)
        n = 0
        for line in chk.splitlines():
            if line.strip().isdigit():
                n = int(line.strip())
        self.log_line("[SELinux] 校验：system_server 相关规则 %d 条（>0 即成功）" % n)
        self.status("SELinux 已放行（重启后需再点一次，或刷 Magisk 模块固化）", n > 0)

    def apply_all(self):
        if not self.check_ready():
            return
        self.write_params()
        self.restart_app()
        self.status("已应用并重启 App", True)
        self.root.after(2500, self.do_query)

    def refresh_mod_ver(self):
        """同步补一次模块版本探测（异步 probe 有时序，别让功能被旧值挡住）"""
        d = self.adb.sh("dumpsys package %s" % PKG_MODULE)
        m = re.search(r"versionCode=(\d+)", d)
        if m:
            self.mod_ver = int(m.group(1))
            self.v_ver.set("模块: %s" % ("未安装" if self.mod_ver == 0
                                        else "v%d 已安装" % self.mod_ver))
        return self.mod_ver

    def do_query(self):
        def work():
            # 版本探测是异步的，这里顺手补一次，避免"明明装了 v15 却说要 v8"
            if self.mod_ver < 8:
                self.refresh_mod_ver()
            r = self.query_rect()
            if r:
                # v20/v21：异常 rect 一律拒收 ——
                #  0x0（task 正在被移除）：会把预览红框变成 0x0 消失掉，没法拖动缩放
                #  接近全屏：过渡期（启动 ~2s 内）窗口还没缩到 PiP 尺寸
                if r["w"] <= 0 or r["h"] <= 0:
                    self.log_q.put(("text", "[查询] 读到 %dx%d 的空 bounds（task 可能在退场），已忽略"
                                    % (r["w"], r["h"])))
                elif r["w"] * r["h"] > self.screen_w * self.screen_h * 0.9:
                    self.log_q.put(("text", "[查询] 读到 %dx%d @ (%d,%d) —— 疑似过渡期全屏态，已忽略"
                                    % (r["w"], r["h"], r["x"], r["y"])))
                else:
                    self.win = r
                    self.log_q.put(("text", "[查询] 电视实际窗口 %dx%d @ (%d,%d)（模块 v%d）"
                                    % (r["w"], r["h"], r["x"], r["y"], self.mod_ver)))
                    self.root.after(0, self.draw)
            else:
                self.log_q.put(("text", "[查询] 没读到窗口：目标 App 可能没在 PiP 模式"
                                        "（先用「零黑屏启动」把它开起来）"))
        threading.Thread(target=work, daemon=True).start()

    def clear_params(self):
        self.pkg = self.v_pkg.get().strip() or DEFAULT_PKG
        self.adb.sh("rm -f /data/local/tmp/floatwin_pip_preset "
                    "/data/local/tmp/floatwin_pip_size "
                    "/data/local/tmp/floatwin_pip_scale "
                    "/data/local/tmp/floatwin_target_dp "
                    "/data/local/tmp/floatwin_pip_sw "
                    "/data/local/tmp/floatwin_pip_orient "
                    "/data/local/tmp/floatwin_pip_pos")
        self.log_line("[清参数] 已恢复出厂默认")
        self.status("参数已清空", True)

    def check_ready(self, silent=False):
        if not self.adb_exe or not os.path.isfile(self.adb_exe):
            if not silent:
                messagebox.showwarning("缺少 adb", "请先指定正确的 adb.exe 路径")
            return False
        self.adb_exe = self.v_adb.get().strip()
        self.adb = Adb(self.adb_exe, self.serial)
        det = self.adb.devices_detail()
        if len(det) > 1 and not self.serial:
            self.set_serial(self.pick_device(det))
            self.log_line("[设备] 检测到 %d 台，已锁定 %s" % (len(det), self.serial))
        if not [d for d in det]:
            if not silent:
                messagebox.showwarning("未连接", "请先点「自动识别」或「扫描局域网」")
            else:
                self.status("未连接（先点「自动识别」）", False)
            return False
        self.pkg = self.v_pkg.get().strip() or DEFAULT_PKG
        self.save_cfg()
        return True

    # ------------------------------------------------ Tab2 安装维护
    def build_tab_install(self, f):
        box = ttk.LabelFrame(f, text="模块安装", padding=8)
        box.pack(fill="x")

        self.v_apk = tk.StringVar(value=os.path.join(HERE, "dist", "FloatWin-v34.apk"))
        ttk.Label(box, text="APK:").grid(row=0, column=0, sticky="w")
        ttk.Entry(box, textvariable=self.v_apk, width=60).grid(row=0, column=1, sticky="w", padx=4)
        ttk.Button(box, text="浏览", command=self.pick_apk).grid(row=0, column=2, padx=2)

        r = ttk.Frame(box)
        r.grid(row=1, column=0, columnspan=3, pady=8, sticky="w")
        ttk.Button(r, text="安装 / 覆盖安装", command=self.install).pack(side="left", padx=2)
        ttk.Button(r, text="卸载模块", command=self.uninstall).pack(side="left", padx=2)
        ttk.Button(r, text="重启电视", command=lambda: self.adb.run("reboot")).pack(side="left", padx=2)
        ttk.Button(r, text="重新检测版本", command=self.probe).pack(side="left", padx=2)

        ttk.Label(box, text="装完必须：LSPosed → 模块 → 勾「悬浮画面」→ 作用域勾"
                            "【系统框架 android】+【SystemUI】+【目标App】→ 重启电视",
                  foreground="#c0392b", wraplength=760, justify="left").grid(
            row=2, column=0, columnspan=3, sticky="w")

        em = ttk.LabelFrame(f, text="应急恢复", padding=8)
        em.pack(fill="x", pady=10)
        r2 = ttk.Frame(em)
        r2.pack(anchor="w")
        ttk.Button(r2, text="① 卸载模块", command=self.uninstall).pack(side="left", padx=2)
        ttk.Button(r2, text="② 只清参数", command=self.clear_params).pack(side="left", padx=2)
        ttk.Button(r2, text="③ 停用 LSPosed（全部 hook 失效）",
                   command=self.disable_lsposed).pack(side="left", padx=2)
        ttk.Button(r2, text="④ 恢复 LSPosed", command=self.enable_lsposed).pack(side="left", padx=2)
        ttk.Label(em, text="SystemUI 崩循环但 adb 还能连时，用 ③；排查完用 ④ 恢复。"
                           "这台机器 adbd 开机自启且不依赖 SystemUI，所以 adb 永远连得上。",
                  foreground="#666", wraplength=760, justify="left").pack(anchor="w", pady=(6, 0))

        # ---------- 杜比视界（PC 侧）----------
        dv = ttk.LabelFrame(f, text="杜比视界（让 PC 那个 HDMI 口走 RGB 4:4:4）", padding=8)
        dv.pack(fill="x", pady=10)
        self.v_dvzip = tk.StringVar(value=os.path.join(
            os.path.dirname(HERE), "optimize", "gpro27u_edid_dv_v2.zip"))
        ttk.Label(dv, text="模块 zip:").grid(row=0, column=0, sticky="w")
        ttk.Entry(dv, textvariable=self.v_dvzip, width=60).grid(row=0, column=1, sticky="w", padx=4)
        ttk.Button(dv, text="浏览", command=self.pick_dvzip).grid(row=0, column=2, padx=2)

        r3 = ttk.Frame(dv)
        r3.grid(row=1, column=0, columnspan=3, pady=6, sticky="w")
        ttk.Button(r3, text="① 推到电视", command=self.dv_push).pack(side="left", padx=2)
        ttk.Button(r3, text="② 诊断端口映射", command=self.dv_diag).pack(side="left", padx=2)
        ttk.Label(r3, text="只改物理 HDMI").pack(side="left", padx=(12, 2))
        self.v_dvport = tk.StringVar(value="2")
        ttk.Entry(r3, textvariable=self.v_dvport, width=4).pack(side="left")
        ttk.Button(r3, text="③ 设定端口", command=self.dv_setport).pack(side="left", padx=2)
        ttk.Button(r3, text="④ 看模块日志", command=self.dv_log).pack(side="left", padx=2)

        ttk.Label(dv, text="① 之后在 Magisk「从本地安装」选 /sdcard/gpro27u_edid_dv_v2.zip。\n"
                          "端口与 EDID 文件是错位对应的：物理 HDMI1→GPro_HDMI4_*、HDMI2→HDMI3_*、"
                          "HDMI3→HDMI1_*、HDMI4→HDMI2_*，务必先点②确认再设③。\n"
                          "改完拔插 PC 那根 HDMI（或重启电视），回 Windows 跑 tools\\edid_check.ps1，"
                          "期望 interface=3 / 12bit_444=1 / 10bit_444=1。\n"
                          "最坏情况只是那个口没信号，电视本体与 adb 完全正常，卸载模块重启即恢复。",
                  foreground="#666", wraplength=760, justify="left").grid(
            row=2, column=0, columnspan=3, sticky="w")

    def pick_dvzip(self):
        p = filedialog.askopenfilename(title="选择 EDID 杜比模块 zip",
                                       initialdir=os.path.join(os.path.dirname(HERE), "optimize"),
                                       filetypes=[("ZIP", "*.zip"), ("所有", "*.*")])
        if p:
            self.v_dvzip.set(p)

    def dv_push(self):
        if not self.check_ready(silent=True):
            return
        z = self.v_dvzip.get().strip()
        if not os.path.isfile(z):
            messagebox.showwarning("找不到 zip", z)
            return

        def work():
            out = self.adb.run("push", z, "/sdcard/gpro27u_edid_dv_v2.zip")
            self.log_q.put(("text", "[杜比] " + out.strip()))
        threading.Thread(target=work, daemon=True).start()
        self.log_line("[杜比] 已推到 /sdcard/gpro27u_edid_dv_v2.zip，"
                      "现在到 Magisk → 从本地安装 选它。")

    def dv_diag(self):
        if not self.check_ready(silent=True):
            return

        def work():
            out = self.adb.sh(
                "for f in /vendor/tvconfig/bsp/board/*/edid_cfg.ini; do "
                "grep -E '^HDMI_EDID_File' $f 2>/dev/null | head -40; done; "
                "echo '--- ports ---'; cat /data/adb/edid_dv_ports 2>/dev/null; echo '(空=全部)'")
            self.log_q.put(("text", "[杜比] 端口映射:\n" + out.strip()))
        threading.Thread(target=work, daemon=True).start()

    def dv_setport(self):
        if not self.check_ready(silent=True):
            return
        p = (self.v_dvport.get() or "").strip()
        if p not in ("1", "2", "3", "4", "all"):
            messagebox.showwarning("端口不对", "只能填 1 / 2 / 3 / 4 / all")
            return
        self.adb.sh("echo %s > /data/adb/edid_dv_ports" % p)
        self.log_line("[杜比] 端口已设为 %s（拔插 HDMI 或重启电视后生效）" % p)

    def dv_log(self):
        if not self.check_ready(silent=True):
            return

        def work():
            out = self.adb.sh("cat /data/local/tmp/edid_dv.log 2>/dev/null | tail -40")
            self.log_q.put(("text", "[杜比] 模块日志:\n" + (out.strip() or "(空：模块还没跑过)")))
        threading.Thread(target=work, daemon=True).start()

    def pick_apk(self):
        p = filedialog.askopenfilename(title="选择 FloatWin APK",
                                       initialdir=os.path.join(HERE, "dist"),
                                       filetypes=[("APK", "*.apk"), ("所有", "*.*")])
        if p:
            self.v_apk.set(p)

    def install(self):
        if not self.check_ready():
            return
        apk = self.v_apk.get().strip()
        if not os.path.isfile(apk):
            messagebox.showwarning("找不到 APK", apk)
            return

        def work():
            out = self.adb.run("install", "-r", apk)
            self.log_q.put(("text", "[安装] " + out.strip()))
            time.sleep(0.5)
            self.probe()
        threading.Thread(target=work, daemon=True).start()

    def uninstall(self):
        def work():
            out = self.adb.run("uninstall", PKG_MODULE)
            self.log_q.put(("text", "[卸载] " + out.strip()))
            time.sleep(0.5)
            self.probe()
        threading.Thread(target=work, daemon=True).start()

    def disable_lsposed(self):
        out = self.adb.sh("for d in /data/adb/modules/*sposed* /data/adb/modules/*lspd*; "
                          "do [ -d \"$d\" ] && touch \"$d/disable\"; done; "
                          "ls -d /data/adb/modules/* 2>/dev/null")
        self.log_line("[停用LSPosed] " + out.strip().replace("\n", " | "))
        messagebox.showinfo("已标记", "已给 LSPosed 模块打 disable 标记，重启后生效。\n所有 Xposed hook 会一并失效。")

    def enable_lsposed(self):
        out = self.adb.sh("for d in /data/adb/modules/*sposed* /data/adb/modules/*lspd*; "
                          "do [ -d \"$d\" ] && rm -f \"$d/disable\"; done; echo done")
        self.log_line("[恢复LSPosed] " + out.strip())
        messagebox.showinfo("已恢复", "已移除 disable 标记，重启后生效。")

    # ------------------------------------------------ Tab3 日志
    def build_tab_log(self, f):
        top = ttk.Frame(f)
        top.pack(fill="x")
        ttk.Button(top, text="抓取 FloatWin 日志", command=self.grab_log).pack(side="left", padx=2)
        ttk.Button(top, text="抓 LSPosed 日志", command=lambda: self.grab_log("lsposed")).pack(side="left", padx=2)
        ttk.Button(top, text="清空缓冲", command=lambda: self.adb.run("logcat", "-c")).pack(side="left", padx=2)
        ttk.Button(top, text="清空本框", command=lambda: self.logbox.delete("1.0", "end")).pack(side="left", padx=2)
        self.v_autolog = tk.BooleanVar(value=False)
        ttk.Checkbutton(top, text="每 3 秒自动抓", variable=self.v_autolog,
                        command=self.toggle_autolog).pack(side="left", padx=10)

        self.logbox = tk.Text(f, wrap="none", bg="#111", fg="#ddd",
                              font=("Consolas", 9), insertbackground="white")
        self.logbox.pack(fill="both", expand=True, pady=6)
        sb = ttk.Scrollbar(self.logbox, command=self.logbox.yview)
        sb.pack(side="right", fill="y")
        self.logbox.configure(yscrollcommand=sb.set)

    def toggle_autolog(self):
        if self.v_autolog.get():
            self.grab_log()
            self.root.after(3000, self.auto_log)

    def auto_log(self):
        if not self.v_autolog.get():
            return
        self.grab_log()
        self.root.after(3000, self.auto_log)

    def grab_log(self, what="floatwin"):
        def work():
            out = self.adb.sh("logcat -d | grep -i %s | tail -80" % what, timeout=25)
            self.log_q.put(("text", out))
        threading.Thread(target=work, daemon=True).start()

    def log_text(self, s):
        self.logbox.insert("end", s.rstrip() + "\n")
        self.logbox.see("end")

    def log_line(self, s):
        self.logbox.insert("end", s + "\n")
        self.logbox.see("end")

    # ------------------------------------------------ Tab4 说明
    def build_tab_help(self, f):
        txt = """FloatWin 悬浮窗控制台 —— 参数速查

【窗口像素 宽x高】最高优先级。填了它，放大百分比和预设都失效。
  电视横屏建议 851x479；手机竖屏建议 547x972 或 400x711。

【放大 %】只在「窗口像素」留空时生效，按系统原始 PiP 尺寸等比放大。

【逻辑宽度 dp】App 看到的宽度。TV 应用按 960dp 设计，手机 App 按 360~411dp。

【sw 资源桶】最关键的一项。决定 App 用哪套布局：
  sw < 600  → 手机布局      sw >= 600 → 平板/电视布局
  Bettbox 实测是手机 App，sw 设 411 就能拿到手机 UI。

【方向】很多 App 在清单里写死 landscape，窗口竖了内容不竖 —— 把方向设 1（竖屏）强制纠正。

【位置】
  tl/tr/bl/br/c 关键字：改完需重启 App。
  x,y 绝对坐标（如 300,200）：实时生效，或直接拖左边预览框。

【典型配方】
  手机 App 竖屏：预设=手机竖屏 phone，尺寸=547x972，dp=411，sw=411，方向=1
  TV 应用放大：  预设=电视横屏 tv，尺寸=851x479，dp=960，sw 留空，方向=0

【自由缩放】直接拖预览框的四个角或四条边：
  角 = 同时改宽高（默认锁定宽高比）  边 = 只改一个方向
  缩放结果会自动填进「窗口像素」；勾了「缩放后自动重启App」就立刻生效。
  注意：只有「移动」是实时的，「缩放」需要 App 重新进一次 PiP（所以要重启 App）。

【遥控操控】
  PiP 窗口默认拿不到焦点，遥控器按键全被后面的全屏 App 吃掉。
  做法：system_server 抓到方向键/确定键后广播给 PiP 里的 App，
  App 收到后调用自己的 dispatchKeyEvent —— 按键直接进它的 View 树，
  于是上下左右 + 确定就能操控了，不需要真的抢焦点。默认开启。
  关掉：写 0 到 /data/local/tmp/floatwin_key_relay
  排障：日志里出现 KEY relay: <键码> 说明转发成功；出现 KEY native 说明它已自己收到。

【前台还 HDMI】
  黑屏根因：App 进 PiP 后，前台任务会退给"上一个全屏任务"——也就是小米桌面；
  桌面不渲染 HDMI，Windows 侧信号中断重同步，就是你看到的那两下黑。
  模块在 PiP 生效后立刻用 Android 标准 TvInput 框架把 HDMI 直通源拉回前台，
  前台根本不经过桌面，这条链路就不发生。不需要任何私有类名。
  默认开启。关掉：写 0 到 /data/local/tmp/floatwin_home_restore
  指定源：写 inputId 到 /data/local/tmp/floatwin_hdmi_id
          （点「扫描 HDMI 输入源」会列出电视上所有输入源）
  指定组件：写 com.pkg/.Cls 到 /data/local/tmp/floatwin_hdmi_cmp（最高优先级）
  排障：日志里 hdmi-front: 输入源 = ... 会列出全部源；
        hdmi-front: 已拉起 -> ... 说明成功。

【隐形启动（可选，默认关）】
  App 启动那一下的黑，多半是它自己的黑底 splash 盖住了 HDMI。
  开启后会把首屏窗口 alpha 调到 0.01，让 HDMI 透出来。
  带 4 秒无条件恢复安全阀，进 PiP 时也会立刻恢复（否则小窗看不见）。
  打开：写 1 到 /data/local/tmp/floatwin_ghost

【返回键 = 直接强停（v31）】
  按返回不再"退出 PiP 回桌面"，而是让 system_server 直接 forceStop 掉这个 App，
  和烟雨 OTA 工具箱里的「强制停止」一样：窗口直接消失，不经过桌面。
  注意别再去重复拉起 HDMI 直通源 —— 实测那会让 ExternalSourceActivity 重启、
  HDMI 重新握手，就是"返回黑一下"的来源（HDMIF: system 拉起 HDMI 前台）。
  想恢复旧行为：写 1 到 /data/local/tmp/floatwin_back_hdmi
  关闭返回键接管：写 0 到 /data/local/tmp/floatwin_back_exit

【杜比视界（PC 侧）】
  EDID 里杜比 VSVDB 只声明了 YUV 4:2:2（interface=2），所以 Windows 送 4:2:2，
  桌面文字彩边。optimize/gpro27u_edid_dv_v2.zip 把它改成 RGB 4:4:4 源主导
  （interface=3 + 12/10bit 4:4:4），校验和已重算。
  注意端口与文件是错位对应的：物理 HDMI1→GPro_HDMI4_*、HDMI2→HDMI3_*、
  HDMI3→HDMI1_*、HDMI4→HDMI2_*。先点「诊断端口映射」确认，再「设定端口」。
  最坏情况只是那个口没信号，电视本体和 adb 完全正常，卸载模块重启即恢复。

【目标 App 识别】
  「自动识别」会读电视日志：优先取最近一次 enterPip 的 App，
  其次取 LSPosed 作用域里被注入的非系统包。
  「列出应用」= pm list packages -3（第三方），「当前前台」= 现在屏幕上那个。

【电视 IP 识别】
  「自动识别」先看 adb devices（USB 直连就不用填 IP），
  没设备时自动回连上次成功的 IP；都没有就点「扫描局域网」，
  会扫 ARP 表 + 网关同网段里开着 5555 端口的机器并逐个 adb connect。

【不生效时按这个顺序查】
  1. LSPosed 作用域勾了没？系统框架 + SystemUI + 目标App 三个都要
  2. 重启电视了没？系统框架和 SystemUI 的 hook 必须重启
  3. 到「日志」页抓 FloatWin 日志，看有没有 enterPip -> true 和 density 那行
  4. 目标 App 是不是在 PiP 里？只有进了 PiP 才受这些参数影响

【安全】
  这个模块只是 /data/app 下的普通 APK，不写 system/vendor/boot 分区，
  不动 bootloader 和 AVB，理论上不存在变砖路径。最坏情况用「应急恢复」里的 ①②③ 撤销。
"""
        self.help_text = tk.Text(f, wrap="word", font=(FONT, 9), relief="flat",
                                 highlightthickness=0, padx=12, pady=12)
        self.help_text.pack(fill="both", expand=True)
        self.help_text.insert("1.0", txt)
        self.help_text.configure(state="disabled")

    # ------------------------------------------------ 底部
    def build_bottom(self):
        f = ttk.Frame(self.root, padding=(10, 6))
        f.pack(fill="x")
        ttk.Label(f, style="Sub.TLabel",
                  text="改完参数点「应用到电视并重启App」；位置可实时拖动（前提：LSPosed 作用域里也勾了 SystemUI）。"
                       "按遥控器返回 = 直接强停悬浮 App（不回桌面、不碰 HDMI 直通源）。").pack(side="left")


def hide_console():
    """若是用 python.exe（而非 pythonw）启动的，把控制台窗口藏掉"""
    try:
        import ctypes
        hwnd = ctypes.windll.kernel32.GetConsoleWindow()
        if hwnd:
            ctypes.windll.user32.ShowWindow(hwnd, 0)  # SW_HIDE
    except Exception:
        pass


def main():
    hide_console()
    try:
        root = tk.Tk()
        try:
            root.call("tk", "scaling", 1.2)
        except Exception:
            pass
        App(root)
        root.mainloop()
    except Exception:
        # 打包成 exe（无控制台）后看不到 traceback，落到 exe 同目录的 gui_crash.log
        import traceback
        tb = traceback.format_exc()
        try:
            with open(os.path.join(HERE, "gui_crash.log"), "w", encoding="utf-8") as f:
                f.write(tb)
        except Exception:
            pass
        try:
            messagebox.showerror("启动失败", tb[-1500:])
        except Exception:
            pass


if __name__ == "__main__":
    main()
