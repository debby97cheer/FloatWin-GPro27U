package com.gpro27u.floatwin;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 悬浮画面模块 v5 —— PiP 放大 + density 覆盖
 *
 * v4 真机结论（com.appshub.bettbox）：
 *   client hooked ✅ / enterPip -> true ✅ / density 320 -> 95dpi ✅
 *   但放大 miss：这台 ROM 只有 TV 版 wm shell，
 *   com.android.wm.shell.pip.PipBoundsState 不存在，
 *   实际是 com.android.wm.shell.pip.tv.TvPipBoundsState / TvPipBoundsAlgorithm，
 *   且没有 setBounds(Rect)。
 *
 * v5 改法（关键）：
 *   ① 不再猜签名 —— 用反射遍历 getDeclaredMethods()，
 *      按"方法名"匹配后用 XposedBridge.hookMethod 直接挂，
 *      参数类型/返回类型一概不关心。
 *   ② 放大点改为 TvPipBoundsAlgorithm#getEntryDestinationBounds():Rect
 *      （无参、返回 Rect，就是进 PiP 的目标 bounds），改返回值。
 *      同时覆盖 adjustBoundsForTemporaryDecor / getNormalBounds / getEntryBounds /
 *      getDefaultBounds 等所有"返回 Rect 的 bounds 方法"。
 *   ③ 防连锁放大：记住 (输入Rect → 输出Rect)，
 *      若输入等于上次输出 → 原样返回；若输入等于上次输入 → 复用上次输出。
 *
 * v12（本次）：
 *   ① 消黑屏 —— PiP 生效后前台会退给小米桌面，桌面不渲染 HDMI，
 *      于是 Windows 侧信号中断重同步 = 用户看到的"黑一下"。
 *      v12 在进 PiP 后立刻用 Android 标准 TvInput 框架把 HDMI 直通源拉回前台，
 *      前台永不经过桌面，这条链路就不发生。不需要任何私有类名。
 *   ② 隐形启动（默认关）—— 把 App 首屏窗口 alpha 调到 0.01，
 *      让 HDMI 透出来，盖住 App 启动的黑底 splash。带 4 秒无条件恢复安全阀。
 *   ③ 多候选查找（借鉴 HyperTV 的 TargetFinder）—— 按键抓取点
 *      按多个候选类名 + 多个候选方法名逐个探测，命中哪个用哪个，
 *      全不命中只打日志。避免像早期版本那样因类名对不上整个功能失效。
 *
 * 运行时可调（不用重编译）：
 *   /data/local/tmp/floatwin_target_dp   目标逻辑宽度，默认 960（480~1920）
 *   /data/local/tmp/floatwin_pip_scale   放大百分比，默认 150（100~300）
 *   /data/local/tmp/floatwin_home_restore  v12 前台还 HDMI，默认 1（0=关）
 *   /data/local/tmp/floatwin_hdmi_id      指定 HDMI 源 id（留空自动挑第一个含 HDMI 的）
 *   /data/local/tmp/floatwin_hdmi_cmp     显式组件 com.pkg/.Cls（最高优先级）
 *   /data/local/tmp/floatwin_ghost        v12 隐形启动，默认 0（1=开）
 *   /data/local/tmp/floatwin_no_anim      拉起 HDMI 时不带动画，默认 1
 *
 * 作用域：系统框架(android) + SystemUI + 目标 App。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "FloatWin: ";
    private static final int FLAG_PIP = 0x00400000;
    private static final int DEFAULT_PIP_SCALE = 150; // 百分比
    private static final String DEFAULT_PIP_POS = "tr"; // 右上（与系统默认一致）
    private static final int DP_TV = 960;    // 电视布局的逻辑宽度
    private static final int DP_PHONE = 411; // 手机布局的逻辑宽度（≈ Pixel / 主流安卓机）

    /* ---- v8 实时拖动 ---- */
    private static volatile Object sBoundsState;    // TvPipBoundsState 实例
    private static volatile Object sBoundsController = null;  // v25：TvPipBoundsController
    /** v28：SystemUI 自己画的 PiP 装饰层（背景 + 菜单），独立于 App 窗口 */
    private static volatile View sPipBgView = null;
    private static volatile View sPipMenuView = null;
    /** v29：Application#onCreate 捕获的 Context（注册广播用） */
    private static volatile Context sAppCtx = null;
    private static volatile boolean sParkRx = false;
    private static volatile boolean sParkRxLogged = false;
    private static volatile long sParkRxLastTry = 0;   // v30：注册失败后的重试节流
    private static volatile Object sPipController;  // TvPipController 实例
    private static boolean sLiveStarted = false;
    private static volatile int[] sLiveXY = null;   // 当前生效的绝对坐标
    private static volatile int[] sLiveWH = null;   // v18：当前生效的尺寸（-1 = 不缩放，只挪位置）
    private static volatile int sLiveApplyLeft = 0;   // 剩余强制应用次数（防止与系统互抢抖动）
    private static volatile String sLastReported = "";
    private static Handler sSysUiMain;                // SystemUI 主线程（窗口操作必须走它）

    // v17：PiP 菜单视图是建在 wmshell.main 线程上的。之前把移动窗口的操作 post 到
    //      SystemUI 主线程，动画回调回到 main 线程去 touch 视图 →
    //      CalledFromWrongThreadException → SystemUI 直接崩（表现：黑屏回默认桌面）。
    //      这里从 TvPip* 实例的字段里找到 wmshell 的 Handler 并缓存。
    private static volatile Handler sShellHandler = null;
    private static volatile boolean sShellScanDone = false;

    // v17：记录"哪些 Activity 真的进过 PiP"。模块对任意 App 都装了生命周期钩子，
    //      所以退出时不能看 sPipAct（任何 App 一 resume 就会被记成 sPipAct），
    //      否则随便关个 App 都会把电视切回 HDMI。
    private static final WeakHashMap<Object, Boolean> PIPPED = new WeakHashMap<Object, Boolean>();

    // v18：刚退出 PiP 的时间戳。退出后系统可能立刻把该 task 以全屏身份唤起一次，
    //      那一下会把 HDMI 画面顶掉 → 用户看到黑屏。用这个时间窗把它挡回去。
    private static volatile long sPipExitTs = 0L;

    private static final WeakHashMap<Object, Boolean> ENTERED = new WeakHashMap<Object, Boolean>();
    private static final Map<Object, Integer> ORIG_DPI = new HashMap<Object, Integer>();
    private static final Map<Object, Integer> LAST_PX = new HashMap<Object, Integer>();
    private static final WeakHashMap<Object, Rect[]> MEMO = new WeakHashMap<Object, Rect[]>();
    private static Handler sMain;

    /* ---------------- 遥控按键中转（让 PiP 里的 App 能收到遥控器/键盘按键）----------------
     * 原理：PiP 窗口默认拿不到焦点，按键全被后面那个全屏 App（HDMI 输入）吃掉。
     * ① system_server 侧：钩 PhoneWindowManager#interceptKeyBeforeQueueing，
     *    把导航键原样广播给处于 PiP 的那个包；
     * ② 目标 App 侧：动态注册一个广播接收器，收到后调用自己的
     *    Activity#dispatchKeyEvent —— 直接把按键塞进它自己的 View 树，
     *    于是焦点导航（DPAD 上下左右 + 确定）就能用了，不需要真的抢焦点。
     * ③ 去重：如果哪天 PiP 窗口真的拿到焦点（原生按键自己就到了），
     *    中转会自动让位，避免一次按键走两步。
     */
    private static final String RELAY_ACTION = "com.gpro27u.floatwin.RELAY_KEY";
    private static final String TARGET_ACTION = "com.gpro27u.floatwin.SET_TARGET";
    // v16：多 App 互斥。系统只允许一个 pinned task，第二个进 PiP 会把第一个踢成全屏，
    // 于是出现"一个全屏 + 一个悬浮"。这里在新 App 进 PiP 前先把上一个赶走。
    private static final String PIPREQ_ACTION = "com.gpro27u.floatwin.PIP_REQ";
    private static final String PIPEXIT_ACTION = "com.gpro27u.floatwin.PIP_EXIT";
    // v20：App 退到后台后就失去了启动 Activity 的资格（BAL_BLOCK result=102），
    //      补拉 HDMI 必须由 system_server 代劳。App 发这个广播，system 端收。
    private static final String HDMIF_ACTION = "com.gpro27u.floatwin.HDMI_FRONT";
    private static final File F_KEY_TARGET = new File("/data/local/tmp/floatwin_key_target");
    private static final File F_PIP_ACTIVE = new File("/data/local/tmp/floatwin_pip_active");
    private static final File F_HAS_FOCUS = new File("/data/local/tmp/floatwin_pip_hasfocus");
    private static final File F_RELAY_ON = new File("/data/local/tmp/floatwin_key_relay");
    private static final File F_GRAB_ON = new File("/data/local/tmp/floatwin_focus_grab");
    // v12：进 PiP 后把前台还给 HDMI 源（消黑屏）
    private static final File F_HOME_ON = new File("/data/local/tmp/floatwin_home_restore");
    private static final File F_HOME_ID = new File("/data/local/tmp/floatwin_hdmi_id");
    private static final File F_HOME_CMP = new File("/data/local/tmp/floatwin_hdmi_cmp");
    // v12：启动期"隐形窗口"，盖住 App 首屏黑底（默认关）
    private static final File F_GHOST_ON = new File("/data/local/tmp/floatwin_ghost");
    private static final File F_NOANIM = new File("/data/local/tmp/floatwin_no_anim");
    // v18：返回键行为。1（默认）= 按返回直接退出悬浮窗并回到 Windows 画面，
    //      且用「收回后台」而不是 finish，避免 App 以全屏身份闪一下造成黑屏；
    //      写 0 则恢复成"把返回键原样交给 App 自己处理"。
    private static final File F_BACK_EXIT = new File("/data/local/tmp/floatwin_back_exit");
    /** v31：返回键退场时还要不要顺手把 HDMI 直通源再拉一次（默认关 —— 实测它就是黑屏源） */
    private static final File F_BACK_HDMI = new File("/data/local/tmp/floatwin_back_hdmi");
    /** v32：返回键退场时把 HDMI 直通源【带回前台】（默认开）。
     *  和 v30 的区别：v30 用的是纯 NEW_TASK，会把 ExternalSourceActivity 再新建一次
     *  → 直通源重启 → HDMI 重握手 → 黑一下。
     *  这里用 REORDER_TO_FRONT|SINGLE_TOP：只把已经存在的那个实例挪回前台，
     *  走 onNewIntent 不重建，理论上不重新握手。
     *  万一你的电视还是会因为这次挪动黑一下，就 echo 0 > /data/local/tmp/floatwin_back_front */
    private static final File F_BACK_FRONT = new File("/data/local/tmp/floatwin_back_front");
    /** v32：返回键退场时先不先把窗口变透明（默认关，理由见 exitPipToBack 里的注释） */
    private static final File F_BACK_HIDE = new File("/data/local/tmp/floatwin_back_hide");
    /** v33：返回键交给电脑上的控制台执行 am force-stop（默认开）。
     *  关掉就退回"App 自己了断"（会回桌面、可能被拉活）。 */
    private static final File F_BACK_DELEGATE = new File("/data/local/tmp/floatwin_back_delegate");
    /** v27：跨进程"停靠"开关（已作废，见下）。 */
    private static final File F_PARK = new File("/data/local/tmp/floatwin_pip_park");
    /**
     * v29：App → SystemUI 的停靠通知，改用**广播**。
     * 文件方案实测根本没生效过：`ls /data/local/tmp` 里从来没有 floatwin_pip_active，
     * 说明第三方 App 对这个目录没有写权限（SELinux），v23~v28 所有的
     * "写文件通知 SystemUI" 全是空转 —— 装饰层一次都没被收起来，所以残影一直都在。
     */
    private static final String PARK_ACTION = "com.gpro27u.floatwin.PARK";
    private static final String UNPARK_ACTION = "com.gpro27u.floatwin.UNPARK";

    /**
     * v30：App → system_server 的"自尽了断"请求。
     *
     * 用户给的思路：遥控器按返回时干脆把 App 杀掉。
     * 实测比 v23~v29 的"隐身保留 task"更干净——PiP task 一旦消失，
     * SystemUI 那两层装饰（就是屏幕上那块黑色半透明阴影）也会被一起撤掉，
     * 不会再出现"回到桌面还挂着一块残影"。
     *
     * 但【顺序是关键】：PiP 退场的那一瞬间系统会把它下面的 task 抬起来，
     * 而 pinned 下面坐着的正是桌面（app.orchard.tv）→ 就是"回 tv 桌面 + 黑屏"。
     * 所以不能只杀，必须让 system_server 先把 HDMI 直通源顶到前台，再 forceStop：
     *   ① 立刻拉起 tvplayer（前台已经是 HDMI，桌面没机会被抬起来）
     *   ② 稍等 400ms 让 tvplayer 站住
     *   ③ forceStopPackage（system_server 才有这个权限，App 自己没有）
     *   ④ 再补拉一次 HDMI，防 home 抢回
     */
    private static final String KILL_ACTION = "com.gpro27u.floatwin.KILL_ME";

    private static volatile boolean sSelfDispatch = false; // 本进程正在注入中转来的按键
    private static volatile long sLastKeyTime = -1;        // 按键去重
    private static volatile boolean sSinkReady = false;
    private static volatile boolean sExitSinkReady = false;   // v16：让位接收器是否已注册
    private static volatile Activity sPipAct = null;       // 当前处于 PiP 的 Activity
    private static volatile boolean sHidden = false;       // v23：按过返回键、窗口已隐身
    // v27：按返回键后把窗口「停到屏幕外」。比透明隐身干净得多 ——
    // 透明只是 alpha=0，窗口仍参与合成，PiP 的圆角/阴影装饰层照画（就是用户看到的残影），
    // 而且 task 还在屏幕上，电视切桌面时残影会跟着出现在桌面上。
    private static volatile boolean sParked = false;

    /** v32：启动期"持续压制"。系统在 ActivityThread.handleResumeActivity 里会调
     *  Activity.makeVisible() → mDecor.setVisibility(VISIBLE)，把我们刚设的
     *  INVISIBLE 又抹掉 —— 这就是为什么 v31 明明打了 ghost: on，用户还是看到全屏。
     *  所以隐形不能只做一次，得在整段"还没缩到 PiP"的时间里反复压回去。 */
    private static volatile boolean sSuppress = false;
    private static volatile int sSuppressTicks = 0;
    private static volatile String sLastParked = null;

    /* v11：目标 App 用广播上报"我在 PiP"，system_server 记在内存里。
     * 之前靠 App 写 /data/local/tmp/floatwin_key_target，真机上 App（untrusted_app）
     * 写不进 /data/local/tmp，文件一直是空的 → 中转永远不发。改成 Binder 上报，
     * 跨进程不再依赖任何文件权限。 */
    private static volatile String sTargetPkg = "";        // system_server 内存里的目标包名
    // v16：当前占着 PiP 位子的包（只有 system_server 这份才是权威，各 App 进程各有一份副本）
    private static volatile String sPipOwnerPkg = "";
    private static volatile long sPipOwnerTs = 0L;
    private static volatile boolean sPipActiveSys = false; // system_server 内存里的 PiP 状态
    private static volatile boolean sTargetReg = false;    // 上报通道是否登记成功

    // system_server 侧的缓存（避免每次按键都读文件）
    private static volatile Context sSysCtx = null;
    private static volatile String sTargetCache = "";
    private static volatile long sTargetTs = 0;
    private static volatile boolean sActiveCache = false;
    private static volatile long sActiveTs = 0;
    private static volatile long sFocusCache = 0;
    private static volatile long sFocusTs = 0;
    private static volatile boolean sGrabCache = false;
    private static volatile long sGrabTs = 0;
    private static final WeakHashMap<Object, Boolean> ARS = new WeakHashMap<Object, Boolean>();

    /** 候选 PiP 类，逐个探测 */
    private static final String[] PIP_CLASSES = {
            "com.android.wm.shell.pip.tv.TvPipBoundsAlgorithm",
            "com.android.wm.shell.pip.tv.TvPipBoundsState",
            "com.android.wm.shell.pip.tv.TvPipTaskOrganizer",
            "com.android.wm.shell.pip.tv.TvPipController",
            // v25：运行时真正摆放 PiP 的类（applyPlacementBounds / recalculatePipBounds 就在这）
            "com.android.wm.shell.pip.tv.TvPipBoundsController",
            // 下面这几个是从本机 MiTvSystemUI.apk 里实扫出来的（classes.dex 里确认存在）
            "com.android.wm.shell.common.pip.PipBoundsAlgorithm",
            "com.android.wm.shell.pip.tv.TvPipKeepClearAlgorithm",
            "com.android.wm.shell.pip.PipBoundsState",
            "com.android.wm.shell.pip.PipBoundsAlgorithm",
            "com.android.wm.shell.pip.PipTaskOrganizer",
            "com.android.wm.shell.common.pip.PipBoundsState",
            "com.android.systemui.pip.tv.PipManager",
            "com.android.systemui.pip.PipBoundsAlgorithm",
    };

    /** 这些方法"返回 Rect"，放大返回值 */
    private static final String[] RESULT_SCALERS = {
            "getEntryDestinationBounds",
            "getEntryBounds",
            "getNormalBounds",
            "getDefaultBounds",
            "getAdjustedBounds",
            "adjustBoundsForTemporaryDecor",
    };

    /** 这些方法"接收 Rect"，放大入参 */
    private static final String[] ARG_SCALERS = {
            "setBounds",
            "setNormalBounds",
            "setExpandedBounds",
    };

    /** 这些方法跑完之后，把 mTvPipGravity / mDefaultGravity 写成我们想要的位置 */
    private static final String[] GRAVITY_HOOKS = {
            "setBoundsStateForEntry",
            "updateDefaultGravity",
    };

    /**
     * v12：遥控按键的抓取点。不同 ROM / 不同 Android 版本类名与所在位置都不一样，
     * 借鉴 HyperTV 的 TargetFinder —— 列多个候选，逐个探测，命中哪个用哪个，
     * 全都没命中也只是打日志，绝不崩溃。
     */
    private static final String[] KEY_SOURCE_CLASSES = {
            "com.android.server.policy.PhoneWindowManager",
            "com.android.server.policy.MiuiPhoneWindowManager",
            "com.android.server.policy.BasePhoneWindowManager",
    };

    /** 这些方法都可能是"按键进队列前的最后一站" */
    private static final String[] KEY_SOURCE_METHODS = {
            "interceptKeyBeforeQueueing",
            "interceptKeyBeforeDispatching",
            "interceptKeyTi",
    };

    /** 这几个类做深度转储（全量方法 + 字段 + 父类） */
    private static final String[] DEEP_DUMP = {
            "com.android.wm.shell.pip.tv.TvPipBoundsState",
            "com.android.wm.shell.pip.tv.TvPipBoundsAlgorithm",
    };

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (lp == null || lp.packageName == null) return;
        final String pkg = lp.packageName;

        if ("android".equals(pkg)) {
            hookSystemServer(lp.classLoader);
            hookKeySource(lp.classLoader);
            hookFocusGrab(lp.classLoader);
            registerTargetSink();       // v11：登记"目标 App 上报"通道（广播，不依赖文件）
            hookPipBounds(lp.classLoader, pkg);
            hookSafeActivityOptions(lp.classLoader);   // v13：放行"直接启动进 PiP"
            hookSuppressSplash(lp.classLoader);        // v15：别糊全屏首屏
            return;
        }
        if (pkg.startsWith("com.android.systemui")) {
            hookPipBounds(lp.classLoader, pkg);
            return;
        }

        hookPipBounds(lp.classLoader, pkg);

        try {
            sMain = new Handler(Looper.getMainLooper());

            // v12：启动期把窗口调透明，盖住 App 首屏黑底（默认关）
            XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                sAppCtx = (Context) param.thisObject;
                            } catch (Throwable ignore) {
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(Activity.class, "onCreate",
                    android.os.Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                ghostOn((Activity) param.thisObject);
                            } catch (Throwable ignore) {
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity act = (Activity) param.thisObject;
                        if (act == null) return;

                        // v27：解除"停靠"。按过返回键后窗口停在屏幕外，
                        // 用户再次打开悬浮窗复用同一实例时不会走 enterPip 回调，
                        // 必须在这里把开关清掉，否则窗口永远回不来（看着像"启动了但没出来"）。
                        if (F_PARK.exists()) F_PARK.delete();
                        if (sParked || sHidden) {
                            // v29：解除停靠同样要走广播，SystemUI 才会把装饰层放回来
                            sParked = false;
                            sHidden = false;
                            try {
                                Intent up = new Intent(UNPARK_ACTION);
                                up.addFlags(0x10000000);
                                act.getApplicationContext().sendBroadcast(up);
                            } catch (Throwable ignore) {
                            }
                            log("resume：已广播解除停靠，窗口回到屏幕");
                        }

                        // v19：防黑屏网必须放在 ENTERED 判断【之前】。
                        // Activity 第一次 onResume 时就已登记进 ENTERED，而系统把退出 PiP 的 task
                        // 还原成全屏时走的是【同一个实例】的 onResume —— 放在后面会被 return 掉，
                        // 等于这张网从来没生效过（日志 22:37:29.812 "Activity resuming: localsend" 就是证据）。
                        if (System.currentTimeMillis() - sPipExitTs < 3000
                                && android.os.Build.VERSION.SDK_INT >= 26
                                && !act.isInPictureInPictureMode()) {
                            log("防黑屏：拦截退出后的全屏唤起");
                            fadeOut(act);                       // 先变透明，这一帧就挡不住 HDMI
                            try {
                                act.moveTaskToBack(true);
                            } catch (Throwable ignore) {
                            }
                            Context c0 = act.getApplicationContext();
                            if (c0 != null) {
                                restoreHdmiFront(c0, 0);
                                // 再补一枪：系统可能在我们拉完以后又把 task 抬上来
                                final Context c1 = c0;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        restoreHdmiFront(c1, 0);
                                    }
                                }, 500);
                            }
                            return;
                        }

                        // fadeIn 必须在 ENTERED 判断【之前】：App 收进后台后实例还在，
                        // 下次开悬浮窗复用同一实例时 onResume 会被 ENTERED 拦掉，
                        // 不在这里恢复不透明的话，窗口就会一直是透明的（看不见内容）。
                        fadeIn(act);

                        if (ENTERED.containsKey(act)) return;
                        ENTERED.put(act, Boolean.TRUE);
                        // v30：不管这次是"--windowingMode 2 直接进 PiP"还是"先全屏启动再 enterPip"，
                        // 中间都有一段按 1920x1080 渲染的窗口（实测 300~750ms），
                        // 不遮住就是用户说的"启动时会先全屏一下再缩放"。
                        // 以前只有 direct-pinned 那条路走了隐形，普通 enterPip 那条路完全没遮 →
                        // 窗口模式一变（系统放行/拒绝 PINNED 启动）就露馅。
                        // 现在一律先隐形，等外框真的落到 PiP 尺寸再显形。
                        // 【安全边界】：桌面 / HDMI 直通源 / 系统界面绝不能隐形，
                        // 否则等于把电视画面关掉几秒 —— ghostCandidate 负责挡掉这些。
                        if (ghostCandidate(act)) ghostOnForce(act);
                        // v14：被 --windowingMode 2 直接拉进 PiP 时，系统不会再走 enterPip
                        // 和 onPictureInPictureModeChanged 回调，density/方向/遥控全都不会执行
                        // → 必须在这里补上，否则就是"小横窗 + 电视布局"
                        // v16：先登记自己 + 注册让位通道 + 向 system_server 抢位，
                        // 让旧 App 有机会体面退场，而不是被系统踢成全屏
                        sPipAct = act;
                        registerExitSink(act);
                        requestPipSlot(act);

                        if (android.os.Build.VERSION.SDK_INT >= 26 && act.isInPictureInPictureMode()) {
                            log("direct-pinned: 直接以 PiP 启动，跳过 enterPip");
                            // v21：这段窗口会先全屏渲染 ~750ms 才缩到 PiP 尺寸，
                            // 强制隐形遮住（waitPipBounds 就位后 ghostOff 显形）
                            ghostOnForce(act);
                            onPipEnteredSetup(act, false);
                            return;
                        }
                        // 等 system_server 把上一个赶走再进 PiP（300ms 足够广播往返）
                        sMain.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                tryEnterPip(act, 0);
                            }
                        }, 300);
                    } catch (Throwable t) {
                        log("onResume hook error: " + t);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(Activity.class, "onPictureInPictureModeChanged",
                    boolean.class, Configuration.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                onPipChanged((Activity) param.thisObject, (Boolean) param.args[0]);
                            } catch (Throwable t) {
                                log("pipChanged hook error: " + t);
                            }
                        }
                    });

            // v17：悬浮窗被关掉（遥控器按返回 / 被别的 App 顶掉 / 被驱逐）时，
            // 前台会掉回桌面，桌面不渲染 HDMI → 显示器黑屏。
            // 这里在 Activity 销毁前把 HDMI 直通源重新拉回前台，回到 Windows 画面。
            // 只对本进程"真的进过 PiP"的 Activity 生效，别的 App 一律不碰。
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Activity act = (Activity) param.thisObject;
                        if (act == null) return;
                        if (!PIPPED.containsKey(act)) return;
                        PIPPED.remove(act);
                        log("PiP 退出（" + act.getPackageName() + "）→ 还原 HDMI 前台");
                        final Context ctx = act.getApplicationContext();
                        if (ctx == null) return;
                        if (sMain != null) {
                            sMain.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    restoreHdmiFront(ctx, 0);
                                }
                            }, 250);
                        } else {
                            restoreHdmiFront(ctx, 0);
                        }
                    } catch (Throwable t) {
                        log("onDestroy hook: " + t);
                    }
                }
            });

            log("client hooked in " + pkg);
        } catch (Throwable t) {
            log("client hook failed in " + pkg + ": " + t);
        }

        try {
            hookKeySink();
        } catch (Throwable t) {
            log("key sink hook failed: " + t);
        }
    }

    /* ---------------- system_server：放行 PiP 门禁 ---------------- */

    private static void hookSystemServer(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.wm.ActivityRecord", cl,
                    "supportsPictureInPicture", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.thisObject != null) ARS.put(param.thisObject, Boolean.TRUE);
                            } catch (Throwable ignore) {
                            }
                            param.setResult(Boolean.TRUE);
                        }
                    });
            log("system_server: hooked ActivityRecord.supportsPictureInPicture");
        } catch (Throwable t) {
            log("system_server: hook failed: " + t);
        }
    }

    /* ---------------- 按键中转：system_server 侧（按键来源）---------------- */

    private static void hookKeySource(ClassLoader cl) {
        StringBuilder seen = new StringBuilder();
        int hooked = 0;

        for (String cn : KEY_SOURCE_CLASSES) {
            Class<?> c;
            try {
                c = XposedHelpers.findClass(cn, cl);
            } catch (Throwable t) {
                seen.append(" [miss:").append(cn).append("]");
                continue;
            }
            for (String mn : KEY_SOURCE_METHODS) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!mn.equals(m.getName())) continue;
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length < 1 || !KeyEvent.class.isAssignableFrom(ps[0])) continue;
                    try {
                        XposedBridge.hookMethod(m, KEY_SOURCE_HOOK);
                        hooked++;
                        log("KEYHOOK OK: " + cn + "#" + mn);
                    } catch (Throwable t) {
                        log("KEYHOOK err " + cn + "#" + mn + ": " + t);
                    }
                }
            }
            // 记录该类里所有跟按键有关的方法名，方便下次定位
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                if (n.indexOf("Key") >= 0 || n.indexOf("Dispatch") >= 0) {
                    seen.append(" ").append(n);
                }
            }
        }

        if (hooked == 0) {
            log("KEYHOOK miss: 候选类里都没找到可用按键方法；候选方法名=" + seen);
        } else {
            log("KEYHOOK ready: hooked=" + hooked);
        }
    }

    private static final XC_MethodHook KEY_SOURCE_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object[] a = param.args;
                if (a == null || a.length == 0) return;
                if (!(a[0] instanceof KeyEvent)) return;
                relayKey((KeyEvent) a[0]);
            } catch (Throwable ignore) {
            }
        }
    };

    /** 只转发导航键，音量/电源/HOME 之类不碰 */
    private static boolean isNavKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9;
        }
    }

    private static void relayKey(KeyEvent ev) {
        try {
            if (ev == null || !relayEnabled()) return;
            int code = ev.getKeyCode();
            if (!isNavKey(code)) return;
            Context ctx = systemContext();
            if (ctx == null) return;

            // 目标包名：① system_server 内存（App 广播上报的，最新）② 文件（adb/root 手写的兜底）
            String target = sTargetPkg;
            if (target == null || target.length() == 0) target = readTrim(F_KEY_TARGET);
            // PiP 是否活着：内存优先，其次文件；两个都没时就全局广播，
            // 收到广播的 App 自己判断"我是不是在 PiP"，不是就丢掉（见 handleRelayedKey）。
            boolean active = sPipActiveSys || "1".equals(readTrim(F_PIP_ACTIVE));

            Intent it = new Intent(RELAY_ACTION);
            if (target != null && target.length() > 0) {
                it.setPackage(target);          // 定向：只发给 PiP 里的那个 App
            } else if (!active) {
                return;                          // 连 PiP 都没有，没必要广播
            }
            it.putExtra("code", code);
            it.putExtra("act", ev.getAction());
            it.putExtra("t", ev.getEventTime());
            it.putExtra("dt", ev.getDownTime());
            it.putExtra("rep", ev.getRepeatCount());
            it.putExtra("meta", ev.getMetaState());
            it.putExtra("src", ev.getSource());
            it.addFlags(0x10000000); // FLAG_RECEIVER_FOREGROUND
            ctx.sendBroadcast(it);
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                log("KEY out: " + code + " -> " + (target != null && target.length() > 0 ? target : "ALL"));
            }
        } catch (Throwable ignore) {
        }
    }

    /* ---------------- v11：目标 App 用广播上报 PiP 状态 ---------------- */

    /** system_server 侧：登记一个动态接收器，收 App 上报的"我在 PiP / 我退了 PiP" */
    private static void registerTargetSink() {
        try {
            final Context ctx = systemContext();
            if (ctx == null) {
                log("TARGEREG: 拿不到 system context");
                return;
            }
            final BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        if (i == null || !TARGET_ACTION.equals(i.getAction())) return;
                        sTargetPkg = i.getStringExtra("pkg");
                        sPipActiveSys = i.getBooleanExtra("on", false);
                        log("target <= " + sTargetPkg + " (PiP=" + sPipActiveSys + ")");
                    } catch (Throwable ignore) {
                    }
                }
            };
            // v16：PiP 位子仲裁 —— 新 App 抢位时，先把上一个占位的赶走
            final BroadcastReceiver arb = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        if (i == null || !PIPREQ_ACTION.equals(i.getAction())) return;
                        onPipRequested(i.getStringExtra("pkg"));
                    } catch (Throwable ignore) {
                    }
                }
            };
            final IntentFilter fa = new IntentFilter(PIPREQ_ACTION);
            final IntentFilter f = new IntentFilter(TARGET_ACTION);
            // v20：system uid 拉起 HDMI 前台（App 自己拉会被 BAL 拦）
            final android.content.BroadcastReceiver hdmiR = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        if (i == null || !HDMIF_ACTION.equals(i.getAction())) return;
                        hdmiFrontBySystem(i.getStringExtra("cmp"));
                        log("HDMIF: system 拉起 HDMI 前台");
                    } catch (Throwable t) {
                        log("HDMIF fail: " + t);
                    }
                }
            };
            // v30：App 请求"拉回 HDMI 前台 + 把自己了断"（遥控器返回键）
            final android.content.BroadcastReceiver killR = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        if (i == null || !KILL_ACTION.equals(i.getAction())) return;
                        final String pkg = i.getStringExtra("pkg");
                        if (pkg == null || pkg.length() == 0) return;
                        // v31：只强停，别的什么都不做。
                        // 之前会顺手把 tvplayer 再拉一次前台，实测那正是"返回黑一下"的来源
                        // （ExternalSourceActivity 被重启 → HDMI 重协商）。
                        // 烟雨 OTA 工具箱的强停不会黑，就是因为它只做 force-stop。
                        log("KILL: 收到 " + pkg + " 的强停请求 → forceStop");
                        final Handler hh = new Handler(Looper.getMainLooper());
                        hh.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                forceStopPkg(pkg);
                            }
                        }, 250);
                    } catch (Throwable t) {
                        log("KILL fail: " + t);
                    }
                }
            };
            final IntentFilter fk = new IntentFilter(KILL_ACTION);
            final IntentFilter fh = new IntentFilter(HDMIF_ACTION);
            Runnable reg = new Runnable() {
                @Override
                public void run() {
                    try {
                        try {
                            ctx.registerReceiver(r, f, 2); // RECEIVER_EXPORTED：允许第三方 App 发进来
                        } catch (Throwable t) {
                            ctx.registerReceiver(r, f);
                        }
                        try {
                            ctx.registerReceiver(arb, fa, 2);
                        } catch (Throwable t) {
                            ctx.registerReceiver(arb, fa);
                        }
                        try {
                            ctx.registerReceiver(hdmiR, fh, 2);
                        } catch (Throwable t) {
                            ctx.registerReceiver(hdmiR, fh);
                        }
                        try {
                            ctx.registerReceiver(killR, fk, 2);
                        } catch (Throwable t) {
                            ctx.registerReceiver(killR, fk);
                        }
                        sTargetReg = true;
                        log("TARGEREG OK：已登记目标上报通道（不再依赖 /data/local/tmp）");
                    } catch (Throwable t) {
                        log("TARGEREG fail: " + t);
                    }
                }
            };
            try {
                new Handler(Looper.getMainLooper()).post(reg);
            } catch (Throwable t) {
                reg.run();
            }
        } catch (Throwable t) {
            log("TARGEREG fail2: " + t);
        }
    }

    /** v16：某个 App 要进 PiP 了 —— 如果位子上已经坐着别人，先请它下去 */
    private static void onPipRequested(String pkg) {
        if (pkg == null || pkg.length() == 0) return;
        final String old = sPipOwnerPkg;
        if (old == null || old.length() == 0 || old.equals(pkg)) {
            if (!pkg.equals(old)) {
                sPipOwnerPkg = pkg;
                sPipOwnerTs = System.currentTimeMillis();
            }
            return;
        }
        sPipOwnerPkg = pkg;
        sPipOwnerTs = System.currentTimeMillis();
        log("PIPREQ: " + pkg + " 抢位，驱逐上一个 -> " + old);
        try {
            Intent it = new Intent(PIPEXIT_ACTION);
            it.putExtra("pkg", old);
            it.addFlags(0x10000000);          // FLAG_RECEIVER_INCLUDE_BACKGROUND
            final Context ctx = systemContext();
            if (ctx != null) ctx.sendBroadcast(it);
        } catch (Throwable t) {
            log("PIPREQ broadcast fail: " + t);
        }
        // 兜底：广播可能被 App 的接收器漏掉（进程已死/未注册），1.2s 后强杀，
        // 否则它会被系统踢成全屏，画面被抢走
        final String victim = old;
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (victim.equals(sPipOwnerPkg)) return;   // 它又成了当前主人，别杀
                    forceStopPkg(victim);
                }
            }, 1200);
        } catch (Throwable t) {
            forceStopPkg(victim);
        }
    }

    /** v30：以 system uid 把 HDMI 直通源顶到前台（App 自己拉会被后台启动限制拦掉）。
     *  cmp 为空时用小米电视的 ExternalSourceActivity。 */
    private static void hdmiFrontBySystem(String cmp) {
        try {
            Intent it = new Intent();
            if (cmp != null && cmp.indexOf('/') > 0) {
                int s = cmp.indexOf('/');
                String p = cmp.substring(0, s), cls = cmp.substring(s + 1);
                if (cls.startsWith(".")) cls = p + cls;
                it.setClassName(p, cls);
            } else {
                it.setClassName("com.xiaomi.mitv.tvplayer",
                        "com.xiaomi.mitv.tvplayer.ExternalSourceActivity");
            }
            it.addFlags(0x10000000);   // FLAG_ACTIVITY_NEW_TASK
            it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            systemContext().startActivity(it);
        } catch (Throwable t) {
            log("hdmi(sys) fail: " + t);
        }
    }

    /** 兜底强停（system_server 有 FORCE_STOP_PACKAGES 权限） */
    private static void forceStopPkg(String pkg) {
        try {
            Class<?> amCls = Class.forName("android.app.ActivityManager");
            java.lang.reflect.Method gs = amCls.getDeclaredMethod("getService");
            gs.setAccessible(true);
            Object am = gs.invoke(null);
            if (am == null) return;
            java.lang.reflect.Method fs =
                    am.getClass().getMethod("forceStopPackage", String.class, int.class);
            fs.invoke(am, pkg, 0);
            log("EVICT: forceStop " + pkg);
        } catch (Throwable t) {
            log("EVICT fail " + pkg + ": " + t);
        }
    }

    /** 目标 App 侧：上报"我在 PiP"（或"我退了"）给 system_server */
    private static void reportTarget(String pkg, boolean on) {
        try {
            Intent it = new Intent(TARGET_ACTION);
            // 不指定包名：隐式广播一定能送到 system_server 那个动态接收器
            it.putExtra("pkg", pkg);
            it.putExtra("on", on);
            it.addFlags(0x10000000);
            try {
                if (sPipAct != null) {
                    sPipAct.sendBroadcast(it);
                    return;
                }
            } catch (Throwable ignore) {
            }
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object cur = at.getDeclaredMethod("currentActivityThread").invoke(null);
                Object app = at.getDeclaredMethod("getApplication").invoke(cur);
                if (app instanceof Context) ((Context) app).sendBroadcast(it);
            } catch (Throwable ignore) {
            }
        } catch (Throwable ignore) {
        }
    }

    private static Context systemContext() {
        if (sSysCtx != null) return sSysCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getDeclaredMethod("currentActivityThread").invoke(null);
            sSysCtx = (Context) at.getDeclaredMethod("getSystemContext").invoke(cur);
        } catch (Throwable t) {
            log("sysctx fail: " + t);
        }
        return sSysCtx;
    }

    /* ---------------- 焦点强占（实验）：让 PiP 窗口真的可聚焦 ---------------- */

    private static void hookFocusGrab(ClassLoader cl) {
        try {
            Class<?> ws = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            int n = 0;
            for (Method m : ws.getDeclaredMethods()) {
                if (!"canReceiveKeys".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!grabEnabled()) return;
                            if (!isTargetWindow(p.thisObject)) return;
                            if (Boolean.FALSE.equals(p.getResult())) {
                                p.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                });
                n++;
            }
            log("FOCUSGRAB: WindowState#canReceiveKeys x" + n
                    + "（默认关闭，echo 1 > /data/local/tmp/floatwin_focus_grab 开启）");
        } catch (Throwable t) {
            log("FOCUSGRAB fail: " + t);
        }
        try {
            Class<?> ar = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", cl);
            for (Method m : ar.getDeclaredMethods()) {
                if (!"isFocusable".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!grabEnabled()) return;
                            if (!isTargetAr(p.thisObject)) return;
                            if (Boolean.FALSE.equals(p.getResult())) {
                                p.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                });
            }
        } catch (Throwable ignore) {
        }
    }

    private static boolean isTargetWindow(Object ws) {
        try {
            Object ar = XposedHelpers.getObjectField(ws, "mActivityRecord");
            return isTargetAr(ar);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isTargetAr(Object ar) {
        try {
            if (ar == null) return false;
            String pn = (String) XposedHelpers.getObjectField(ar, "packageName");
            String t = cachedTarget();
            return t != null && t.length() > 0 && t.equals(pn);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ---------------- 按键中转：目标 App 侧（按键落地）---------------- */

    private static void hookKeySink() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent", KeyEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Activity a = (Activity) param.thisObject;
                                if (a == null || !a.isInPictureInPictureMode()) return;
                                KeyEvent ev = (KeyEvent) param.args[0];
                                if (ev == null) return;

                                // v29：【回桌面的根因就在这里】
                                // 遥控器按键是通过系统输入通道直接派发给 App 的，
                                // 我们自己的 relay 通道只是"多一份"，拦了它没用 —— 原生这份照进不误，
                                // App 在自己处理 BACK 时就会 finish / 退出 PiP（日志实证：
                                // 19:03:48 按返回后立刻出现 `target <= ... (PiP=false)`），
                                // 系统随即把 tvhome 抬起来并回收 HDMI 直通源 → 黑屏回桌面。
                                // 所以必须在这个真正的入口把 BACK 吞掉（DOWN 和 UP 都吞，
                                // 很多 App 是在 UP 里执行返回的）。
                                if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK
                                        && !sSelfDispatch
                                        && flagOn(F_BACK_EXIT, true)) {
                                    param.setResult(Boolean.TRUE);   // 已消费，App 不会收到
                                    log("KEY back(原生) -> 已吞掉，App 不会自己退场");
                                    return;
                                }

                                if (ev.getAction() != KeyEvent.ACTION_DOWN) return;
                                long t = ev.getEventTime();
                                if (t == sLastKeyTime) {
                                    param.setResult(Boolean.FALSE); // 同一个事件已经处理过
                                    return;
                                }
                                sLastKeyTime = t;
                                if (!sSelfDispatch) {
                                    // 原生按键自己就到了 PiP 窗口 → 它有焦点，通知中转让位
                                    writeFile(F_HAS_FOCUS, String.valueOf(System.currentTimeMillis()));
                                    log("KEY native: " + ev.getKeyCode() + " (PiP 自己收到，中转让位)");
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
        } catch (Throwable t) {
            log("dispatchKeyEvent hook fail: " + t);
        }
    }

    private static void setupKeySink(final Activity act) {
        try {
            writeFile(F_KEY_TARGET, act.getPackageName());  // 尽力而为（App 多半写不进，仅作兜底）
            writeFile(F_PIP_ACTIVE, "1");
            sPipAct = act;
            reportTarget(act.getPackageName(), true);       // v11：广播上报，主通道
            if (sSinkReady) return;
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    handleRelayedKey(i);
                }
            };
            IntentFilter f = new IntentFilter(RELAY_ACTION);
            try {
                act.registerReceiver(r, f, 2); // Context.RECEIVER_EXPORTED (API33+)
            } catch (Throwable t) {
                act.registerReceiver(r, f);
            }
            sSinkReady = true;
            log("key sink ready in " + act.getPackageName() + "（遥控器按键将转发进来）");
        } catch (Throwable t) {
            log("key sink fail: " + t);
        }
        registerExitSink(act);   // v16：收得到"请让位"的广播
    }

    /** v16：注册"让位"接收器 —— 别的 App 要进 PiP 时，system_server 会点名让我退场 */
    private static void registerExitSink(final Activity act) {
        try {
            if (sExitSinkReady) return;
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    handlePipExit(i);
                }
            };
            IntentFilter f = new IntentFilter(PIPEXIT_ACTION);
            try {
                act.registerReceiver(r, f, 2);
            } catch (Throwable t) {
                act.registerReceiver(r, f);
            }
            sExitSinkReady = true;
            log("exit sink ready in " + act.getPackageName() + "（可自动让位）");
        } catch (Throwable t) {
            log("exit sink fail: " + t);
        }
    }

    /** v16：被点名让位 —— 退出悬浮、收进后台，而不是被系统踢成全屏抢画面 */
    private static void handlePipExit(Intent i) {
        try {
            if (i == null || !PIPEXIT_ACTION.equals(i.getAction())) return;
            String victim = i.getStringExtra("pkg");
            final Activity a = sPipAct;
            if (a == null || victim == null) return;
            if (!victim.equals(a.getPackageName())) return;
            log("PIPEXIT: 让位 -> 收进后台 " + victim);
            try {
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            a.moveTaskToBack(true);
                        } catch (Throwable ignore) {
                        }
                        try {
                            a.finishAndRemoveTask();   // 连 task 一起收掉，不留全屏残影
                        } catch (Throwable t) {
                            try {
                                a.finish();
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                log("PIPEXIT apply fail: " + t);
            }
        } catch (Throwable ignore) {
        }
    }

    private static void handleRelayedKey(Intent i) {
        try {
            if (i == null || !RELAY_ACTION.equals(i.getAction())) return;
            final Activity a = sPipAct;
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            // v23：窗口已隐身（按过返回键）→ 遥控彻底交还给电视，按键别再进 App
            if (sHidden) return;
            int code = i.getIntExtra("code", 0);
            if (code == 0) return;

            // v18：返回键 = 退出悬浮窗并回到 Windows 画面。
            // 关键是不走 finish()：App 一 finish/退出 PiP，系统会把它的 task 还原成全屏，
            // 那一瞬间 HDMI 直通画面被顶掉，就是用户看到的黑屏。
            // 这里改成「直接收回后台 + 立刻把 HDMI 拉回前台」，App 全程不占全屏。
            // v28：BACK 的【按下和抬起都要吞掉】。
            // 以前只拦 DOWN，UP 照常 dispatch 给 App —— App 自己在 UP 里执行返回逻辑
            // → finish / 退出 PiP → 系统抬桌面 + 回收 HDMI 直通源（黑屏）。
            // 这就是"返回总是回 tv 桌面、而且黑两次"的真正来源：
            // 黑两次 = 我们退一次 + App 又退一次。
            if (code == KeyEvent.KEYCODE_BACK && flagOn(F_BACK_EXIT, true) && isInPipNow(a)) {
                if (i.getIntExtra("act", KeyEvent.ACTION_DOWN) == KeyEvent.ACTION_DOWN) {
                    log("KEY back -> 收起窗口与 PiP 装饰层（v28）");
                    exitPipToBack(a);
                } else {
                    log("KEY back(up) -> 吞掉，不让 App 自己退场");
                }
                return;
            }

            long t = i.getLongExtra("t", SystemClock.uptimeMillis());
            long dt = i.getLongExtra("dt", t);
            final KeyEvent ev = new KeyEvent(dt, t, i.getIntExtra("act", KeyEvent.ACTION_DOWN),
                    code, i.getIntExtra("rep", 0), i.getIntExtra("meta", 0));
            sSelfDispatch = true;
            try {
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            a.dispatchKeyEvent(ev);
                        } catch (Throwable ignore) {
                        } finally {
                            sSelfDispatch = false;
                        }
                    }
                });
            } catch (Throwable tt) {
                sSelfDispatch = false;
            }
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                log("KEY relay: " + code);
            }
        } catch (Throwable ignore) {
        }
    }

    /** v18：这个 Activity 现在是不是正处在 PiP 里（两种判据都认，容错） */
    private static boolean isInPipNow(Activity a) {
        if (a == null) return false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && a.isInPictureInPictureMode()) return true;
        } catch (Throwable ignore) {
        }
        return PIPPED.containsKey(a);
    }

    /**
     * v18：退出悬浮窗的正确姿势 —— 收回后台，而不是 finish。
     * finish / 退出 PiP 会让系统把 task 还原成全屏（顶掉 HDMI → 黑屏），
     * moveTaskToBack 则全程不占全屏，再把 HDMI 直通源拉回前台即可无缝回到 Windows 画面。
     */
    /**
     * v23：退出悬浮窗 = "隐身 + 把 HDMI 顶到前面"，**绝不拆 task**。
     *
     * 之前四版（v18~v21）都是 fadeOut → 拉 HDMI → moveTaskToBack → finishAndRemoveTask。
     * 10-23 08:12:23.8 的完整日志证明这条路必回桌面：
     *   23.828 moveTaskToBack(Task#69 localsend)
     *   23.865 onTaskVanished ... state=PipTransitionState(mState=exiting-pip)   ← PiP 退场
     *   24.052 top=app.orchard.tv.MainActivity                                    ← 桌面已抢到前台
     *   24.704 Transition#69 t=CLOSE ... Task#66 type=home m=TO_FRONT,
     *                                    Task#68 tvplayer m=CLOSE                ← HDMI 被一起关掉
     * 也就是说：只要让 PiP 退场，电视就会顺手把桌面抬起来，并把直通源回收。
     *
     * 所以现在改成：窗口置透明 + 不可聚焦（视觉与输入都不挡 HDMI），task 原样留在 PiP。
     * 屏幕上看到的就是纯 HDMI 画面，且没有任何 task 切换 → 没有黑屏、没有桌面。
     * 代价是 App 进程还活着（相当于"最小化到透明"），再启动时直接显形，反而更快。
     */
    private static void exitPipToBack(final Activity a) {
        try {
            long now = System.currentTimeMillis();
            // v27：遥控器一次按键会送来 DOWN + repeat，节流掉重复触发，
            // 否则会连做两次退场动作 —— 就是用户说的"返回两次、黑两次"。
            if (now - sPipExitTs < 1500) {
                log("back：1.5s 内重复返回，忽略");
                return;
            }
            sPipExitTs = now;
            PIPPED.remove(a);
            final Context ctx = a.getApplicationContext();
            final String pkg = a.getPackageName();
            markPipInactive();
            sParked = true;
            sHidden = true;          // 遥控器按键别再进 App，彻底交还给电视

            // v33：把"怎么杀"交出去 —— 交给电脑上的控制台执行 am force-stop。
            // 为什么不自己了断：GUI 上按「强制停止」用户实测【不黑屏、不回桌面、
            // App 直接消失】；而 App 自己 finish / kill 都会让系统抬一个 task 上来
            // （抬的正是桌面），而且带前台服务的 App 杀完还会被系统拉活 ——
            // 20:40:57 日志里 task 明明"已移除"，20:41:02 却还在收遥控按键，就是被拉活了。
            // 所以这里只干两件事：吞掉按键 + 打一行 BACKKEY 日志，等电脑来收。
            if (flagOn(F_BACK_DELEGATE, true)) {
                log("BACKKEY " + pkg);
                if (sMain != null) {
                    sMain.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            log("back：3 秒没等到电脑来收，自己了断 " + pkg);
                            try {
                                a.finishAndRemoveTask();
                            } catch (Throwable ignore) {
                            }
                            try {
                                android.os.Process.killProcess(android.os.Process.myPid());
                            } catch (Throwable ignore) {
                            }
                        }
                    }, 3000);
                }
                return;
            }

            // v32：这里【不再】立刻把窗口变透明。
            // v31 的做法是"先透明 + 1.2 秒之后才收尾"，结果这 1.2 秒里
            // App 窗口是透明了、SystemUI 画的 PiP 装饰层却还好端端盖在上面 ——
            // 用户说的"黑屏"其实就是这块黑色半透明装饰层在撑满屏幕。
            // 现在改成 450ms 内直接把 task 一起收掉（见 ④），窗口和装饰层同时消失。
            // 想恢复旧行为就 echo 1 > /data/local/tmp/floatwin_back_hide
            if (flagOn(F_BACK_HIDE, false)) {
                hidePipWindow(a);
            }

            // v30 ②：顺手请 SystemUI 收起 PiP 装饰层（那块黑色半透明阴影）。
            // 注册成功就即时生效；注册不成功也没关系 —— ④ 会把整个 App 连 task 一起清掉。
            try {
                Intent pk = new Intent(PARK_ACTION);
                pk.addFlags(0x10000000);   // FLAG_RECEIVER_INCLUDE_BACKGROUND
                if (ctx != null) ctx.sendBroadcast(pk);
                log("back：已广播收起请求（装饰层）");
            } catch (Throwable t2) {
                log("back 广播失败: " + t2);
            }
            writeFile(F_PARK, "1");   // 备用通道（有权限时有效）

            // v31：这整段【默认关】。
            // 20:10 的实测日志证明黑屏就是这么来的：返回时把 tvplayer 的
            // ExternalSourceActivity 又 start 了一次（HDMIF: system 拉起 HDMI 前台 x2），
            // 直通源被重启 → HDMI 重新握手 → 电视黑一下。
            // 而用户用"烟雨 OTA 工具箱"直接强制停止同一个 App 时并不会黑，
            // 说明"纯强停、什么都不碰"才是正解。要恢复旧行为就写 1 到
            // /data/local/tmp/floatwin_back_hdmi。
            if (!flagOn(F_BACK_HDMI, false)) {
                log("back：不再重复拉起 HDMI 直通源（避免重协商黑屏）");
            } else {
                try {
                    if (ctx != null) sendHdmiFront(ctx);
                } catch (Throwable ignore) {
                }
                if (sMain != null) {
                    sMain.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (ctx != null) sendHdmiFront(ctx);
                            } catch (Throwable ignore) {
                            }
                        }
                    }, 600);
                }
            }

            // v32 ②：把 HDMI 直通源【带回前台】—— 注意是"挪回来"而不是重新 start。
            // v30 用纯 NEW_TASK 会把 ExternalSourceActivity 再新建一次 → 直通源重启
            // → HDMI 重握手 → 黑一下（日志里那两行 HDMIF 就是证据）。
            // 这里用 REORDER_TO_FRONT|SINGLE_TOP：只把已经存在的那个实例挪到前台，
            // 走 onNewIntent 不重建，理论上不重新握手。
            // 目的很单纯：PiP task 退场时系统总要抬一个 task 上来，
            // 让 tvplayer 先站住，桌面就没机会被抬出来（就是"回到 tv 桌面"）。
            // 如果你的电视还是会因为这一下黑屏：echo 0 > /data/local/tmp/floatwin_back_front
            if (flagOn(F_BACK_FRONT, true)) {
                try {
                    bringHdmiFrontNoRestart(ctx);
                } catch (Throwable t2) {
                    log("back：带回 HDMI 前台失败: " + t2);
                }
            } else {
                log("back：已关闭「带回 HDMI 前台」（floatwin_back_front=0）");
            }

            // v32 ③：直接请 root 代执行一次 am force-stop —— 跟 GUI 上「强制停止」
            // 那条命令一模一样，也正是用户实测"不会黑屏"的那条路径。
            // App 自己没有 FORCE_STOP_PACKAGES 权限，只能借 su 的手；
            // 借得到就是最干净的结果，借不到还有 ④⑤ 兜底。
            suForceStop(pkg);

            // v32 ④：450ms 后 finishAndRemoveTask —— task 一并收掉，
            // SystemUI 那两层 PiP 装饰层随 task 一起消失，不会再留"黑色阴影"。
            // 注意：不再先做 hidePipWindow 了 —— v31 那 1.2s 的"透明期"里
            // App 窗口透明了、装饰层却还在，用户看到的正是那块黑。
            if (sMain != null) {
                sMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            a.finishAndRemoveTask();
                            log("back：已移除 task " + pkg);
                        } catch (Throwable ignore) {
                            try {
                                a.finish();
                            } catch (Throwable ignore2) {
                            }
                        }
                    }
                }, 450);
                // v32 ⑤：最后兜底 —— 进程一定得死，绝不留下一个"看不见却还活着"的空壳
                sMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            android.os.Process.killProcess(android.os.Process.myPid());
                            log("back：兜底自尽了断 " + pkg);
                        } catch (Throwable ignore) {
                        }
                    }
                }, 900);
            }
        } catch (Throwable t) {
            log("exitPipToBack: " + t);
        }
    }

    /** v32：把 HDMI 直通源挪回前台，但【不重建】它。
     *  NEW_TASK + REORDER_TO_FRONT + SINGLE_TOP：实例已存在时走 onNewIntent。 */
    private static void bringHdmiFrontNoRestart(Context ctx) {
        if (ctx == null) return;
        Intent it = new Intent();
        it.setClassName("com.xiaomi.mitv.tvplayer",
                "com.xiaomi.mitv.tvplayer.ExternalSourceActivity");
        it.addFlags(0x10000000);   // FLAG_ACTIVITY_NEW_TASK
        it.addFlags(0x00020000);   // FLAG_ACTIVITY_REORDER_TO_FRONT
        it.addFlags(0x20000000);   // FLAG_ACTIVITY_SINGLE_TOP
        ctx.startActivity(it);
        log("back：已把 HDMI 直通源挪回前台（不重建）");
    }

    /** v32：借 su 执行一次 am force-stop。等价于 GUI 上的「强制停止」。 */
    private static void suForceStop(final String pkg) {
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // 实测这台电视的 su 在 /product/bin/su（不在 /system/xbin），
                    // 而且 App 进程的 PATH 未必包含它 —— 所以显式按绝对路径逐个试。
                    String[] sus = {"/product/bin/su", "su", "/system/bin/su",
                                    "/system/xbin/su", "/vendor/bin/su"};
                    Process p = null;
                    Throwable last = null;
                    for (int i = 0; i < sus.length; i++) {
                        try {
                            p = Runtime.getRuntime().exec(
                                    new String[]{sus[i], "-c", "am force-stop " + pkg});
                            last = null;
                            break;
                        } catch (Throwable t) {
                            last = t;
                        }
                    }
                    try {
                        if (p != null) {
                            try {
                                p.waitFor(1500L, java.util.concurrent.TimeUnit.MILLISECONDS);
                            } catch (Throwable ignore) {
                            }
                            log("back：已借 su 执行 am force-stop " + pkg);
                        } else {
                            log("back：su 起不来（走兜底）: " + last);
                        }
                    } catch (Throwable t) {
                        log("back：su force-stop 走不通（很正常，走兜底）: " + t);
                    } finally {
                        if (p != null) {
                            try {
                                p.destroy();
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                }
            }).start();
        } catch (Throwable t) {
            log("back：su 线程起不来: " + t);
        }
    }

    /**
     * v29：SystemUI 进程里注册"收起/恢复装饰层"广播。
     * App 端写不了 /data/local/tmp（SELinux），所以只能靠广播跨进程喊话。
     */
    private static void registerParkReceiver() {
        if (sParkRx) return;
        // v30：失败时别再每 250ms 重试一次 —— 19:57 那一段日志里
        // "Given caller package android is not running in process" 刷了整整两屏，
        // 一次也没成功，纯属白烧 CPU。改成每 5 秒最多试一次。
        long nowMs = System.currentTimeMillis();
        if (nowMs - sParkRxLastTry < 5000) return;
        sParkRxLastTry = nowMs;
        try {
            Context ctx = null;
            // v30：只认"本进程自己的" Context。
            // 以前第一个候选就是 ActivityThread.getSystemContext()，它的包名是 android，
            // 而 android 这个包并不跑在 SystemUI 进程里 → AMS 直接抛 SecurityException。
            // 这就是 v29 的广播通道从来没建立起来的真正原因（装饰层一次都没被收起）。
            if (sPipBgView != null) {
                try {
                    ctx = sPipBgView.getContext();
                } catch (Throwable ignore) {
                }
            }
            if (ctx == null && sPipMenuView != null) {
                try {
                    ctx = sPipMenuView.getContext();
                } catch (Throwable ignore) {
                }
            }
            if (ctx == null) ctx = sAppCtx;      // Application.onCreate 里拿的，一定是本进程的
            if (ctx == null) {
                try {
                    Class<?> at = Class.forName("android.app.ActivityThread");
                    Object th = at.getDeclaredMethod("currentActivityThread").invoke(null);
                    try {
                        ctx = (Context) at.getDeclaredMethod("getApplication").invoke(th);
                    } catch (Throwable t1) {
                        try {
                            ctx = (Context) at.getDeclaredMethod("currentApplication").invoke(null);
                        } catch (Throwable t2) {
                        }
                    }
                } catch (Throwable ignore) {
                }
            }
            try {
                // 最后一道保险：包名是 android 的 Context 一定会炸，宁可不注册
                if (ctx != null && "android".equals(ctx.getPackageName())) ctx = null;
            } catch (Throwable ignore) {
            }
            if (ctx == null) {
                if (!sParkRxLogged) {
                    sParkRxLogged = true;
                    log("park receiver：暂时拿不到 SystemUI 的 Context（等 PiP 起来后再试）");
                }
                return;
            }
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        if (i == null) return;
                        String act = i.getAction();
                        if (PARK_ACTION.equals(act)) {
                            sParked = true;
                            sLastParked = null;
                            enforceChrome(false);
                            log("park：收到广播 → 收起 PiP 装饰层");
                        } else if (UNPARK_ACTION.equals(act)) {
                            sParked = false;
                            sLastParked = null;
                            sLiveXY = null;      // 下一 tick 重新按 pos 文件摆位
                            sLiveApplyLeft = 4;
                            enforceChrome(true);
                            log("park：收到广播 → 恢复 PiP 装饰层");
                        }
                    } catch (Throwable t) {
                        log("park receiver: " + t);
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(PARK_ACTION);
            f.addAction(UNPARK_ACTION);
            try {
                ctx.registerReceiver(r, f, 2);      // RECEIVER_EXPORTED：允许第三方 App 发进来
            } catch (Throwable t) {
                ctx.registerReceiver(r, f);
            }
            sParkRx = true;
            log("park receiver OK（广播通道就绪）");
        } catch (Throwable t) {
            log("park receiver fail: " + t);
        }
    }

    /**
     * v28：抓住 SystemUI 自己画的 PiP 装饰层 —— 背景 + 菜单。
     *
     * dumpsys SurfaceFlinger 实证：除了 App 自己的窗口，还有两层是 SystemUI 画的
     *   PipBackgroundViewLeash#293 / PipBackgroundView#294 / PipMenuView#297
     * 它们挂在窗口容器上，跟 App 窗口是两码事，App 那边 alpha 设成 0 完全管不到。
     * 所以以前"把窗口调透明"之后，屏幕上还剩一层黑色半透明的框 ——
     * 就是这个背景层；电视切回桌面时它照样跟着出现（因为它属于 PiP 容器，不属于桌面）。
     * 要彻底不留残影，必须把这两层一起收起来。
     */
    private static void capturePipChrome(ClassLoader cl) {
        String[] names = {
                "com.android.wm.shell.pip.tv.TvPipBackgroundView",
                "com.android.wm.shell.pip.tv.TvPipMenuView"
        };
        for (final String cn : names) {
            try {
                Class<?> c = XposedHelpers.findClass(cn, cl);
                XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object o = param.thisObject;
                            if (!(o instanceof View)) return;
                            if (cn.endsWith("BackgroundView")) sPipBgView = (View) o;
                            else sPipMenuView = (View) o;
                            log("capture chrome <- " + cn);
                        } catch (Throwable ignore) {
                        }
                    }
                });
            } catch (Throwable t) {
                log("chrome hook miss " + cn + ": " + t);
            }
        }
    }

    /** v28：收起/恢复 PiP 装饰层（View 操作，必须跑在主线程） */
    private static void enforceChrome(final boolean vis) {
        try {
            // v28：PiP 装饰层是 wmshell.main 线程建的（SystemUI 主线程去碰会抛
            // CalledFromWrongThreadException，就是日志里那两行），必须 post 到那个线程。
            Handler h = shellHandler();
            if (h == null) h = (sMain != null ? sMain : new Handler(Looper.getMainLooper()));
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean did = false;
                        View bg = sPipBgView;
                        if (bg != null && bg.getVisibility() != (vis ? View.VISIBLE : View.GONE)) {
                            bg.setVisibility(vis ? View.VISIBLE : View.GONE);
                            bg.setAlpha(vis ? 1f : 0f);   // 双保险：GONE 之后再归零透明度
                            did = true;
                        }
                        View mv = sPipMenuView;
                        if (mv != null && mv.getVisibility() != (vis ? View.VISIBLE : View.GONE)) {
                            mv.setVisibility(vis ? View.VISIBLE : View.GONE);
                            mv.setAlpha(vis ? 1f : 0f);
                            did = true;
                        }
                        if (did) log("chrome: " + (vis ? "恢复" : "收起") + " PiP 装饰层");
                    } catch (Throwable t) {
                        log("chrome: " + t);
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /** v27（已作废）：把 PiP 窗口整体推到屏幕左侧外面（直接给负坐标，不做 clamp）。
     *  必须在 SystemUI 进程、且跑在 wmshell.main 线程上调用。 */
    private static void parkOffscreen() {
        try {
            final Handler h = shellHandler();
            if (h == null) {
                log("park：拿不到 wmshell handler，跳过（避免崩 SystemUI）");
                return;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object st = sBoundsState;
                        Rect cur = st == null ? null : rawBounds(st);
                        if (cur == null || cur.isEmpty()) {
                            int[] d = dispSize();
                            cur = new Rect(d[0] / 2, 0, d[0] / 2 + 400, 400);
                        }
                        int w = cur.width() > 0 ? cur.width() : 400;
                        int hgt = cur.height() > 0 ? cur.height() : 400;
                        int top = Math.max(0, cur.top);
                        Rect off = new Rect(-(w + 400), top, -400, top + hgt);
                        if (st != null) writeBounds(st, off);
                        applyPlacement(off);
                        log("park -> " + off.toShortString());
                    } catch (Throwable t) {
                        log("park fail: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            log("park: " + t);
        }
    }

    /** v20：请 system_server 代为拉起 HDMI 前台（绕开后台启动限制） */
    private static void sendHdmiFront(Context ctx) {
        try {
            Intent it = new Intent(HDMIF_ACTION);
            String cmp = readTrim(F_HOME_CMP);
            if (cmp != null && cmp.length() > 0) it.putExtra("cmp", cmp);
            it.addFlags(0x10000000);   // FLAG_RECEIVER_INCLUDE_BACKGROUND
            ctx.sendBroadcast(it);
        } catch (Throwable t) {
            log("sendHdmiFront: " + t);
        }
    }

    /** v19：把 Activity 整个变透明（View 树 + 窗口属性双管，SurfaceView 也能盖住）。
     *  用途：退出悬浮窗的过渡期间不让这一帧遮住 HDMI。 */
    private static void fadeOut(final Activity a) {
        if (a == null) return;
        try {
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        a.getWindow().getDecorView().setAlpha(0f);
                    } catch (Throwable ignore) {
                    }
                    try {
                        android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                        lp.alpha = 0f;
                        a.getWindow().setAttributes(lp);
                    } catch (Throwable ignore) {
                    }
                    log("fadeOut：窗口已透明");
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /** v23：隐身 —— 透明 + 不抢焦点/不接触摸，彻底让位给 HDMI（但不拆 task） */
    private static void hidePipWindow(final Activity a) {
        if (a == null) return;
        try {
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        a.getWindow().getDecorView().setAlpha(0f);
                    } catch (Throwable ignore) {
                    }
                    try {
                        android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                        lp.alpha = 0f;
                        lp.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                        a.getWindow().setAttributes(lp);
                    } catch (Throwable ignore) {
                    }
                    log("hidePipWindow：窗口已透明且不再抢焦点");
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /** v19：恢复不透明（正常进入/重新进入悬浮窗时调用）
     *  v23：顺带清掉隐身时加的 NOT_FOCUSABLE / NOT_TOUCHABLE，否则遥控进不来。 */
    private static void fadeIn(final Activity a) {
        if (a == null) return;
        try {
            a.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        a.getWindow().getDecorView().setAlpha(1f);
                        // v31：顺带恢复可见性（INVISIBLE 是 ghost 期间设的，必须在这里解开）
                        if (a.getWindow().getDecorView().getVisibility() != View.VISIBLE) {
                            a.getWindow().getDecorView().setVisibility(View.VISIBLE);
                        }
                    } catch (Throwable ignore) {
                    }
                    try {
                        android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                        lp.alpha = 1f;
                        lp.flags &= ~(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                        a.getWindow().setAttributes(lp);
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static void markPipInactive() {
        try {
            writeFile(F_PIP_ACTIVE, "0");
            String pkg = sPipAct == null ? "" : sPipAct.getPackageName();
            if (pkg != null && pkg.length() > 0) reportTarget(pkg, false);
            sPipAct = null;
        } catch (Throwable ignore) {
        }
    }

    /* ---------------- 文件读写小工具 ---------------- */

    private static void writeFile(File f, String v) {
        try {
            FileOutputStream o = new FileOutputStream(f);
            o.write(v.getBytes("UTF-8"));
            o.flush();
            o.close();
        } catch (Throwable ignore) {
        }
    }

    private static String readTrim(File f) {
        try {
            if (!f.exists()) return "";
            BufferedReader r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            r.close();
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String cachedTarget() {
        long now = SystemClock.elapsedRealtime();
        if (now - sTargetTs > 800) {
            sTargetCache = readTrim(F_KEY_TARGET);
            sTargetTs = now;
        }
        return sTargetCache;
    }

    private static boolean pipActiveCached() {
        long now = SystemClock.elapsedRealtime();
        if (now - sActiveTs > 500) {
            sActiveCache = "1".equals(readTrim(F_PIP_ACTIVE));
            sActiveTs = now;
        }
        return sActiveCache;
    }

    /** PiP 窗口最近 4 秒内收到过原生按键 → 认为它已有焦点 */
    private static boolean hasFocusRecently() {
        long now = SystemClock.elapsedRealtime();
        if (now - sFocusTs > 500) {
            long v = 0;
            try {
                v = Long.parseLong(readTrim(F_HAS_FOCUS));
            } catch (Throwable ignore) {
            }
            sFocusCache = v;
            sFocusTs = now;
        }
        return sFocusCache > 0 && (System.currentTimeMillis() - sFocusCache) < 4000;
    }

    /** 中转总开关，默认开（写 0 关闭） */
    private static boolean relayEnabled() {
        return !"0".equals(readTrim(F_RELAY_ON));
    }

    /** 焦点强占实验开关，默认关（写 1 开启） */
    private static boolean grabEnabled() {
        long now = SystemClock.elapsedRealtime();
        if (now - sGrabTs > 1000) {
            sGrabCache = "1".equals(readTrim(F_GRAB_ON));
            sGrabTs = now;
        }
        return sGrabCache;
    }

    /* ---------------- wm shell：PiP 窗口放大 ---------------- */

    private static void hookPipBounds(ClassLoader cl, String pkg) {
        boolean any = false;
        for (String cn : PIP_CLASSES) {
            Class<?> c;
            try {
                c = XposedHelpers.findClass(cn, cl);
            } catch (Throwable ignore) {
                continue; // 这个类不在这个进程里
            }
            any = true;
            log("PIPCLASS OK: " + cn + " (in " + pkg + ")");
            captureInstances(cn, c);
            if (isDeep(cn)) deepDump(cn, c);
            hookByName(cn, c);
        }
        if (any && pkg.startsWith("com.android.systemui")) {
            capturePipChrome(cl);
            registerParkReceiver();
            startLiveDaemon();
        }
        if (!any && (pkg.startsWith("com.android") || "android".equals(pkg))) {
            log("PIPCLASS none in " + pkg);
        }
    }

    /** 抓实例：构造函数 + 已知 hook 点双管齐下 */
    private static void captureInstances(final String cn, Class<?> c) {
        try {
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object o = param.thisObject;
                    if (o == null) return;
                    if (cn.endsWith("PipBoundsState")) {
                        sBoundsState = o;
                        log("capture BoundsState <- " + cn);
                    } else if (cn.endsWith("PipBoundsController")) {   // v25
                        sBoundsController = o;
                        log("capture BoundsController <- " + cn);
                    } else if (cn.endsWith("PipController")) {
                        sPipController = o;
                        log("capture Controller <- " + cn);
                    }
                }
            });
        } catch (Throwable t) {
            log("ctor hook miss " + cn + ": " + t);
        }
    }

    private static boolean isDeep(String cn) {
        for (String s : DEEP_DUMP) if (s.equals(cn)) return true;
        return false;
    }

    /** 按方法名 hook —— 完全不依赖签名 */
    private static void hookByName(String cn, Class<?> c) {
        Method[] ms;
        try {
            ms = c.getDeclaredMethods();
        } catch (Throwable t) {
            log("declaredMethods failed " + cn + ": " + t);
            return;
        }
        for (Method m : ms) {
            String n = m.getName();
            boolean isGravity = contains(GRAVITY_HOOKS, n);
            boolean isLive = !isGravity && cn.endsWith("BoundsState") && "getBounds".equals(n);
            boolean isResult = !isGravity && !isLive && contains(RESULT_SCALERS, n);
            boolean isArg = !isGravity && !isLive && !isResult && contains(ARG_SCALERS, n);
            // v25：运行时摆放走的是 getTvPipPlacement（不是 getEntryDestinationBounds），
            // 不改它的话，任何一次重算都会把窗口弹回系统算出来的位置。
            boolean isPlace = !isGravity && !isLive && !isResult && !isArg
                    && "getTvPipPlacement".equals(n);
            if (!isGravity && !isLive && !isResult && !isArg && !isPlace) continue;

            XC_MethodHook cb = isGravity ? GRAVITY_HOOK
                    : isLive ? LIVE_HOOK
                    : isPlace ? PLACEMENT_HOOK
                    : (isResult ? RESULT_HOOK : ARG_HOOK);
            try {
                XposedBridge.hookMethod(m, cb);
                log("PIPHOOK OK: " + cn + "#" + n
                        + (isGravity ? " (写位置)" : isLive ? " (实时拖动)" :
                           isPlace ? " (运行时摆放)" :
                           isResult ? " (放大返回值)" : " (放大入参)"));
            } catch (Throwable t) {
                log("PIPHOOK miss: " + cn + "#" + n + " -> " + t);
            }
        }
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) if (s.equals(v)) return true;
        return false;
    }

    /** 把 TvPipBoundsState 里的 gravity 字段写成我们配置的位置（系统自己也会照它摆） */
    private static final XC_MethodHook GRAVITY_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                applyGravity(param.thisObject);
            } catch (Throwable t) {
                log("gravity hook failed: " + t);
            }
        }
    };

    private static void applyGravity(Object state) {
        if (state == null) return;
        if (sBoundsState == null) sBoundsState = state;
        int g = gravityFromPos(readPipPos());
        boolean hit = false;
        try {
            Field f = findField(state.getClass(), "mTvPipGravity");
            if (f != null) { f.setAccessible(true); f.setInt(state, g); hit = true; }
        } catch (Throwable ignore) {
        }
        try {
            Field f = findField(state.getClass(), "mDefaultGravity");
            if (f != null) { f.setAccessible(true); f.setInt(state, g); hit = true; }
        } catch (Throwable ignore) {
        }
        if (hit) log("gravity -> " + g + " (" + readPipPos() + ")");
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null) {
            try {
                return k.getDeclaredField(name);
            } catch (Throwable ignore) {
                k = k.getSuperclass();
            }
        }
        return null;
    }

    /** "tr"/"tl"/"br"/"bl"/"c" → Android Gravity；绝对坐标 → 按矩形落在屏幕哪一侧推算 */
    private static int gravityFromPos(String pos) {
        if ("tl".equals(pos)) return 0x30 | 0x03; // TOP|LEFT
        if ("bl".equals(pos)) return 0x50 | 0x03; // BOTTOM|LEFT
        if ("br".equals(pos)) return 0x50 | 0x05; // BOTTOM|RIGHT
        if ("c".equals(pos)) return 0x11;         // CENTER

        // v25：绝对坐标以前一律返回 TOP|RIGHT，于是系统任何一次重算都把窗口拽回右上角
        // （用户观感就是"拖到哪都在右上"）。这里改成按矩形中心落在哪三分之一来算。
        int[] xy = parseXY(pos);
        if (xy == null) return 0x30 | 0x05;
        int[] d = dispSize();
        int w = (xy.length >= 4 && xy[2] > 0) ? xy[2] : Math.max(80, d[0] / 4);
        int h = (xy.length >= 4 && xy[3] > 0) ? xy[3] : Math.max(80, d[1] / 3);
        int cx = xy[0] + w / 2;
        int cy = xy[1] + h / 2;
        int gv = cy < d[1] / 3 ? 0x30                      // TOP
                : (cy > d[1] * 2 / 3 ? 0x50 : 0x10);       // BOTTOM : CENTER_VERTICAL
        int gh = cx < d[0] / 3 ? 0x03                      // LEFT
                : (cx > d[0] * 2 / 3 ? 0x05 : 0x01);       // RIGHT : CENTER_HORIZONTAL
        return gv | gh;
    }

    /** 位置查询请求：PC 端 touch 一下这个文件，我们把当前 bounds 打进日志 */
    private static final File F_QUERY = new File("/data/local/tmp/floatwin_pip_query");
    /** v23：把"电视上真正生效的窗口矩形"持续写到文件，供 PC 端预览框校准（排错用） */
    private static final File F_ACTUAL = new File("/data/local/tmp/floatwin_pip_actual");
    private static volatile String sLastActual = "";

    /* ---------------- v8：实时拖动 ---------------- */

    /**
     * 让"读 bounds"的人一律看到平移后的 Rect。
     * 这是拖动能生效的关键：系统所有计算都从 getBounds() 取值。
     */
    private static final XC_MethodHook LIVE_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                int[] xy = sLiveXY;
                if (xy == null) return;
                Object r = param.getResult();
                if (!(r instanceof Rect)) return;
                Rect cur = (Rect) r;
                if (cur.isEmpty()) return;
                // v18：配了尺寸就按尺寸报，否则只平移
                Rect want = (sLiveWH != null && sLiveWH[0] > 0 && sLiveWH[1] > 0)
                        ? sized(cur, new int[]{xy[0], xy[1], sLiveWH[0], sLiveWH[1]})
                        : shifted(cur, xy[0], xy[1]);
                if (want == null || want.equals(cur)) return;
                param.setResult(want);
            } catch (Throwable t) {
                // 吞掉，绝不让 SystemUI 崩
            }
        }
    };

    /**
     * v25：运行时摆放走的是 TvPipBoundsAlgorithm#getTvPipPlacement()，
     * 返回的 Placement 里才是真正会被贴到窗口上的 bounds。
     * 不改它的话，系统任何一次重算（配置变化 / keep-clear / 菜单收起）都会把窗口弹回原位。
     */
    private static final XC_MethodHook PLACEMENT_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                int[] xy = sLiveXY;
                if (xy == null || sLiveWH == null) return;
                Object p = param.getResult();
                if (p == null) return;
                Field f = findField(p.getClass(), "bounds");
                if (f == null) return;
                f.setAccessible(true);
                Object cur = f.get(p);
                if (!(cur instanceof Rect)) return;
                Rect want = sized((Rect) cur, new int[]{xy[0], xy[1], sLiveWH[0], sLiveWH[1]});
                if (want == null) return;
                f.set(p, want);
                Field u = findField(p.getClass(), "unstashDestinationBounds");
                if (u != null) { u.setAccessible(true); u.set(p, new Rect(want)); }
                Field a = findField(p.getClass(), "anchorBounds");
                if (a != null) { a.setAccessible(true); a.set(p, new Rect(want)); }
            } catch (Throwable t) {
                log("placement hook: " + t);
            }
        }
    };

    /**
     * v25：真正把窗口摆到 want。
     * 之前（v8~v24）全部只是在改 PipBoundsState.mBounds 这个"记账值" ——
     * dumpsys 实测真窗口一直停在进 PiP 那一刻的 Rect(1249,0-1847,949)，
     * 而日志里 mBounds 早已被我们写成别的值：两边根本不是一回事。
     * TvPipBoundsController.applyPlacementBounds(int 动画时长, Rect bounds) 才是真入口。
     */
    private static boolean applyPlacement(Rect want) {
        Object bc = sBoundsController;
        if (bc == null) return false;
        for (Method m : bc.getClass().getDeclaredMethods()) {
            if (!"applyPlacementBounds".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 2 || ps[0] != int.class || ps[1] != Rect.class) continue;
            try {
                m.setAccessible(true);
                m.invoke(bc, Integer.valueOf(0), new Rect(want));
                log("place -> " + want.toShortString());
                return true;
            } catch (Throwable t) {
                log("place fail: " + t + (t.getCause() != null ? " / cause=" + t.getCause() : ""));
                return false;
            }
        }
        log("place: 找不到 applyPlacementBounds(int,Rect)");
        return false;
    }

    /** 保持宽高，把 Rect 挪到 (x,y) */
    private static Rect shifted(Rect cur, int x, int y) {
        int[] d = dispSize();
        int w = cur.width(), h = cur.height();
        if (w <= 0 || h <= 0) return null;
        int left = Math.max(0, Math.min(x, d[0] - w));
        int top = Math.max(0, Math.min(y, d[1] - h));
        return new Rect(left, top, left + w, top + h);
    }

    /** v18：实时缩放 —— 直接按 (x,y,w,h) 造 Rect，并做边界与宽高比收敛。
     *  Android 对 PiP 宽高比有硬约束（约 0.5 ~ 2.39），越界会被系统直接拉回去，
     *  与其被拉回造成抖动，不如自己先收到合法范围内。 */
    private static Rect sized(Rect cur, int[] xy) {
        int[] d = dispSize();
        int w = xy[2], h = xy[3];
        if (w < 80) w = 80;
        if (h < 80) h = 80;
        if (w > d[0]) w = d[0];
        if (h > d[1]) h = d[1];
        // v20：收窄回系统真正允许的范围。系统 setAspectRatio 的合法区间就是 0.42~2.39，
        // 超出会被 IllegalArgumentException 拒掉。v19 放到 3.0 过宽，这里与 ratio daemon 一致。
        float ar = (float) w / (float) h;
        if (ar > 2.39f) {
            w = (int) (h * 2.39f);
        } else if (ar < 0.42f) {
            h = (int) (w / 0.42f);
        }
        int left = Math.max(0, Math.min(xy[0], d[0] - w));
        int top = Math.max(0, Math.min(xy[1], d[1] - h));
        return new Rect(left, top, left + w, top + h);
    }

    private static int[] dispSize() {
        int dispW = 1920, dispH = 1080;
        try {
            DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
            int a = dm.widthPixels, b = dm.heightPixels;
            if (a > 0 && b > 0) {
                if (a < b) { int t = a; a = b; b = t; }
                dispW = a; dispH = b;
            }
        } catch (Throwable ignore) {
        }
        return new int[]{dispW, dispH};
    }

    /**
     * v17：找到 PiP 菜单视图所属的 wmshell.main 线程的 Handler。
     *
     * 之前把"移动 PiP 窗口"的操作 post 到 SystemUI 主线程，动画回调会在 main 线程
     * 去 touch 建在 wmshell.main 上的菜单视图 →
     *   android.view.ViewRootImpl$CalledFromWrongThreadException
     * → SystemUI 直接崩（用户看到的就是黑屏 + 退回默认桌面）。
     * 所以这里从 TvPip 控制器实例的字段里把 wmshell 的 Handler 翻出来。
     */
    private static Handler shellHandler() {
        if (sShellHandler != null) return sShellHandler;
        Object[] hosts = new Object[]{sPipController, sBoundsState};
        Handler fallback = null;
        StringBuilder sb = new StringBuilder();
        for (Object o : hosts) {
            if (o == null) continue;
            Class<?> c = o.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (!Handler.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (!(v instanceof Handler)) continue;
                        Handler h = (Handler) v;
                        Looper lo = h.getLooper();
                        String tn = (lo == null || lo.getThread() == null) ? "?" : lo.getThread().getName();
                        sb.append(" [").append(f.getName()).append("=").append(tn).append("]");
                        if (tn != null && tn.toLowerCase().contains("wmshell")) {
                            sShellHandler = h;
                        } else if (tn != null && !"main".equals(tn) && fallback == null) {
                            fallback = h;
                        }
                    } catch (Throwable ignore) {
                    }
                }
                c = c.getSuperclass();
            }
        }
        if (sShellHandler == null) sShellHandler = fallback;
        sShellScanDone = sShellHandler != null;
        log("shell handler 扫描:" + sb + " → "
                + (sShellHandler == null ? "没找到（退回 SystemUI 主线程，可能仍会崩）" : "已选定"));
        return sShellHandler;
    }

    private static void startLiveDaemon() {
        if (sLiveStarted) return;
        sLiveStarted = true;
        try {
            sSysUiMain = new Handler(Looper.getMainLooper());
        } catch (Throwable t) {
            log("live handler 创建失败: " + t);
        }
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    log("live daemon started (拖动守护已就绪)");
                    while (true) {
                        try {
                            Thread.sleep(250);
                            tickLive();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }, "FloatWin-live").start();
        } catch (Throwable t) {
            log("live daemon failed: " + t);
        }
    }

    private static void tickLive() {
        try {
            // v29：广播通道延迟注册。模块加载时 SystemUI 的 Application 还没 onCreate，
            // 那时拿不到 Context（实测两次都失败），等进程跑起来再注册就行。
            if (!sParkRx) registerParkReceiver();

            // 位置查询
            if (F_QUERY.exists()) {
                F_QUERY.delete();
                Object st = sBoundsState;
                log("QUERY state=" + (st == null ? "null" : st.getClass().getName())
                        + " cur=" + (st == null ? "?" : String.valueOf(rawBounds(st)))
                        + " want=" + (sLiveXY == null ? "未设绝对坐标" : sLiveXY[0] + "," + sLiveXY[1]));
            }

            // v27：跨进程停靠开关（App 端按返回键写文件，SystemUI 侧负责真挪窗口）。
            // 窗口被推到屏幕外 → 不参与可见区域 → 不留任何残影；
            // task 栈一点不动 → 系统不会抬桌面、不会回收 HDMI 直通源。
            // v29：停靠状态由【广播】设置（文件通道 App 没写权限，只当备用）。
            // 停靠中：不摆位、不读 pos 文件，只维持"装饰层已收起"这个状态。
            // 解除由 UNPARK 广播负责（设 sParked=false 并恢复装饰层）。
            if (sParked || F_PARK.exists()) {
                if (!sParked) {
                    sParked = true;
                    sLastParked = null;
                    log("park：备用文件开关生效");
                }
                enforceChrome(false);   // 系统若把它改回来，这里再收一次
                return;
            }

            int[] xy = parseXY(readPipPos());
            if (xy == null) {
                if (sLiveXY != null) {
                    log("live 关闭（位置改回关键字）");
                    sLiveXY = null;
                    sLiveWH = null;
                    sLiveApplyLeft = 0;
                }
                return;
            }
            int[] whOld = sLiveWH;
            boolean changed = sLiveXY == null
                    || sLiveXY[0] != xy[0] || sLiveXY[1] != xy[1]
                    || whOld == null
                    || whOld[0] != xy[2] || whOld[1] != xy[3];
            if (changed) {
                sLiveXY = xy;
                sLiveWH = new int[]{xy[2], xy[3]};
                sLiveApplyLeft = 4;   // 坐标/尺寸变了就重推几次（系统可能随后又挪一次）
                // v25：坐标一变就同步 gravity，否则下次系统重算会按旧 gravity 把窗口拽回角落
                applyGravity(sBoundsState);
            }
            if (sLiveApplyLeft <= 0) return;
            sLiveApplyLeft--;

            Object st = sBoundsState;
            if (st == null) return;
            Rect cur = rawBounds(st);
            if (cur == null || cur.isEmpty()) return;
            // v23：把真正生效的矩形落盘，PC 端预览框可以拿它跟自己画的红框对账
            String act = cur.left + "," + cur.top + "," + cur.width() + "," + cur.height();
            if (!act.equals(sLastActual)) {
                sLastActual = act;
                writeFile(F_ACTUAL, act);
            }
            // v18：带尺寸就缩放，只有坐标就保持原尺寸平移
            Rect want = (xy[2] > 0 && xy[3] > 0) ? sized(cur, xy) : shifted(cur, xy[0], xy[1]);
            if (want == null) return;

            String rep = want.toShortString();
            if (!rep.equals(sLastReported)) {
                sLastReported = rep;
                log("live -> " + rep + " (原 " + cur.toShortString() + ")");
            }
            if (want.equals(cur)) return;

            // v17：窗口操作必须在 PiP 视图所属的 wmshell.main 线程上做；
            // 丢到 SystemUI 主线程会抛 CalledFromWrongThreadException 崩掉 SystemUI。
            // 找不到 wmshell 线程时宁可不动，也绝不冒险去触发动画。
            final Handler target = shellHandler();
            if (target == null) {
                log("live: 拿不到 wmshell handler，跳过本次移动（避免崩 SystemUI）");
                return;
            }
            final Object stF = st;
            final Rect wantF = want;
            target.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        writeBounds(stF, wantF);
                        // v25：先走真正能挪动窗口的摆放 API，它不在才退回旧的 updatePinnedStackBounds
                        if (!applyPlacement(wantF)) triggerApply();
                    } catch (Throwable t) {
                        log("apply: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            log("tickLive: " + t);
        }
    }

    /** 读"真实" bounds（绕过我们自己 hook 的 getBounds，直接取字段） */
    private static Rect rawBounds(Object st) {
        try {
            Field f = findField(st.getClass(), "mBounds");
            if (f != null) {
                f.setAccessible(true);
                Object o = f.get(st);
                if (o instanceof Rect) return new Rect((Rect) o);
            }
        } catch (Throwable ignore) {
        }
        try {
            Method m = st.getClass().getMethod("getBounds");
            m.setAccessible(true);
            Object o = m.invoke(st);
            if (o instanceof Rect) return new Rect((Rect) o);
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** 写回 bounds：优先 setBounds(Rect) */
    private static void writeBounds(Object st, Rect r) {
        for (Method m : st.getClass().getMethods()) {
            if (!"setBounds".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || ps[0] != Rect.class) continue;
            try {
                m.setAccessible(true);
                m.invoke(st, new Rect(r));
                return;
            } catch (Throwable t) {
                log("setBounds invoke: " + t);
            }
        }
    }

    /** 触发系统把新 bounds 应用到窗口 */
    private static void triggerApply() {
        Object c = sPipController;
        if (c == null) return;
        Method best = null;
        for (Method m : c.getClass().getMethods()) {
            if (!"updatePinnedStackBounds".equals(m.getName())) continue;
            if (m.getParameterTypes().length == 0) { best = m; break; }
            if (best == null) best = m;
        }
        if (best == null) return;
        try {
            best.setAccessible(true);
            if (best.getParameterTypes().length == 0) {
                best.invoke(c);
            } else {
                best.invoke(c, new Object[best.getParameterTypes().length]);
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause();
            log("triggerApply: " + t + (cause != null ? " / cause=" + cause : ""));
        }
    }

    private static final XC_MethodHook RESULT_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object r = param.getResult();
                if (!(r instanceof Rect)) return;
                Rect out = scaleRect(param.thisObject, (Rect) r);
                if (out == null) return;
                param.setResult(out);
                log("pipBounds(出) " + r + " -> " + out.toShortString());
            } catch (Throwable t) {
                log("result scaler failed: " + t);
            }
        }
    };

    private static final XC_MethodHook ARG_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null) return;
                for (int i = 0; i < param.args.length; i++) {
                    if (!(param.args[i] instanceof Rect)) continue;
                    Rect src = (Rect) param.args[i];
                    Rect out = scaleRect(param.thisObject, src);
                    if (out == null) continue;
                    param.args[i] = out;
                    log("pipBounds(入) " + src.toShortString() + " -> " + out.toShortString());
                }
            } catch (Throwable t) {
                log("arg scaler failed: " + t);
            }
        }
    };

    /**
     * 核心放大。返回 null 表示"这次不放"。
     * 连锁保护：输入==上次输出 → 不放；输入==上次输入 → 复用上次输出。
     */
    private static Rect scaleRect(Object self, Rect in) {
        try {
            if (in == null || in.isEmpty()) return null;
            // v22【回退】+ v23【修正】：
            // v22 这里直接 return null，结果把"位置"也一起让出去了 ——
            // scaleRect 是唯一能把坐标真正落到窗口上的通道（系统每次算 bounds 都会读它），
            // 一旦返回 null，系统就完全按默认 gravity(右上) 摆窗口，表现就是"拖动完全失效"。
            // 正确做法是"让出尺寸、保留位置"：按实时坐标 + 实时尺寸造 Rect，
            // 与 ratio daemon 里的 setAspectRatio(w/h) 报同一个值，两条链子不再互相拉扯。
            if (sLiveXY != null && sLiveWH != null && sLiveWH[0] > 0 && sLiveWH[1] > 0) {
                MEMO.remove(self);
                return sized(in, new int[]{sLiveXY[0], sLiveXY[1], sLiveWH[0], sLiveWH[1]});
            }
            int scale = readPipScale();
            if (scale <= 100) return null;

            // live 拖动期间必须禁用缓存：否则同一输入会复用拖动前算出的旧位置，窗口弹回去
            if (sLiveXY != null) {
                MEMO.remove(self);
            } else {
                Rect[] memo = MEMO.get(self);
                if (memo != null) {
                    if (memo[1] != null && in.equals(memo[1])) return null;   // 已是我们放大过的
                    if (memo[0] != null && in.equals(memo[0])) return memo[1]; // 同一输入，复用
                }
            }

            int w = in.width();
            int h = in.height();
            if (w <= 0 || h <= 0) return null;

            int dispW = 1920, dispH = 1080;
            try {
                DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
                int a = dm.widthPixels, b = dm.heightPixels;
                if (a > 0 && b > 0) {
                    if (a < b) { int t = a; a = b; b = t; }
                    dispW = a; dispH = b;
                }
            } catch (Throwable ignore) {
            }

            int nw, nh;
            int[] size = readPipSize();
            if (size != null) {
                // ① 显式指定 WxH（最高优先级）
                nw = Math.min(size[0], Math.round(dispW * 0.95f));
                nh = Math.min(size[1], Math.round(dispH * 0.95f));
            } else if (isPhone()) {
                // ② 竖屏手机预设：9:16，高度取屏高 90%
                nh = Math.round(dispH * 0.9f);
                nw = Math.round(nh * 9f / 16f);
                if (nw > dispW * 0.9f) {
                    nw = Math.round(dispW * 0.9f);
                    nh = Math.round(nw * 16f / 9f);
                }
            } else {
                // ③ 原样按比例放大
                float sf = scale / 100f;
                float maxW = dispW * 0.85f;
                float maxH = dispH * 0.85f;
                if (w * sf > maxW) sf = maxW / w;
                if (h * sf > maxH) sf = maxH / h;
                if (sf <= 1.0001f) return null;
                nw = Math.round(w * sf);
                nh = Math.round(h * sf);
            }
            if (nw <= 0 || nh <= 0) return null;

            String pos = readPipPos();
            int[] xy = parseXY(pos);
            int left, top;
            if (xy != null) {
                left = xy[0];
                top = xy[1];
            } else if ("tl".equals(pos)) {
                left = 0; top = 0;
            } else if ("bl".equals(pos)) {
                left = 0; top = dispH - nh;
            } else if ("br".equals(pos)) {
                left = dispW - nw; top = dispH - nh;
            } else if ("c".equals(pos)) {
                left = (dispW - nw) / 2; top = (dispH - nh) / 2;
            } else {
                left = dispW - nw; top = 0; // tr
            }
            left = Math.max(0, Math.min(left, dispW - nw));
            top = Math.max(0, Math.min(top, dispH - nh));

            Rect out = new Rect(left, top, left + nw, top + nh);
            MEMO.put(self, new Rect[]{new Rect(in), new Rect(out)});
            return out;
        } catch (Throwable t) {
            log("scaleRect failed: " + t);
            return null;
        }
    }

    /** 深度转储：全量方法 + 字段 + 父类，供下一轮精确调整 */
    private static void deepDump(String cn, Class<?> c) {
        try {
            Class<?> sup = c.getSuperclass();
            log("DUMP super: " + cn + " extends " + (sup == null ? "?" : sup.getName()));
            Method[] ms = c.getDeclaredMethods();
            for (Method m : ms) {
                StringBuilder sb = new StringBuilder("DUMPM: " + cn + "#" + m.getName() + "(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(ps[i].getSimpleName());
                }
                sb.append("):").append(m.getReturnType().getSimpleName());
                log(sb.toString());
            }
            Field[] fs = c.getDeclaredFields();
            for (Field f : fs) {
                log("DUMPF: " + cn + " " + f.getType().getSimpleName() + " " + f.getName());
            }
            // 继承来的（父类很可能才是真正存 bounds 的地方）
            Method[] ims = c.getMethods();
            for (Method m : ims) {
                if (m.getDeclaringClass() == c) continue;
                String n = m.getName();
                if (n.indexOf("ound") < 0 && n.indexOf("Size") < 0
                        && n.indexOf("Percent") < 0 && n.indexOf("Aspect") < 0) continue;
                log("DUMPI: (继承)" + m.getDeclaringClass().getSimpleName() + "#" + n
                        + "(" + m.getParameterTypes().length + "):"
                        + m.getReturnType().getSimpleName());
            }
        } catch (Throwable t) {
            log("deepDump failed " + cn + ": " + t);
        }
    }

    /* ---------------- v13：放行「直接启动进 PiP」（根治黑屏）---------------- */

    /**
     * 背景：普通启动 App 必然先全屏，HDMI 前台 Activity（ExternalSourceActivity）被 pause，
     *      电视视频管线切走 → 显示器黑一下；进 PiP 后前台退给桌面 → 再黑；
     *      v12 再把 HDMI 拉回前台 → 又黑。三次黑屏对应三次前台切换。
     * 根治：直接以 PINNED 窗口模式启动 App（`am start-activity --windowingMode 2`），
     *      App 从头到尾不占全屏，HDMI 前台 Activity 一次都不被 pause → 一次都不黑。
     *
     * 障碍：Android 在 SafeActivityOptions.checkPermissions 里校验 MANAGE_ACTIVITY_TASKS，
     *      实测三种身份全被拒：
     *        shell(2000) → Permission Denial ... setLaunchWindowingMode=PINNED
     *        root(0)     → 同样被拒（该校验只认系统 uid）
     *        system(1000)→ Shell commands are only callable by root or shell
     *      即"外部命令"这条路彻底不通，只能从 system_server 内部摘掉这个校验。
     *
     * 影响面：放开后任意调用方都能指定窗口模式。本机是自用电视，风险可接受。
     */
    private static void hookSafeActivityOptions(ClassLoader cl) {
        String[] cands = {
                "com.android.server.wm.SafeActivityOptions",
        };
        for (String cn : cands) {
            Class<?> c;
            try {
                c = XposedHelpers.findClass(cn, cl);
            } catch (Throwable t) {
                log("SAFEOPT: 找不到 " + cn + "（" + t + "）");
                continue;
            }
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!"checkPermissions".equals(m.getName())) continue;
                try {
                    // 用 XC_MethodHook + setResult 跳过原方法，等价于放行；
                    // 不用 XC_MethodReplacement 是因为 d8 对它会报类找不到（desugaring）
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(null);
                        }
                    });
                    n++;
                } catch (Throwable t) {
                    log("SAFEOPT hook 失败 " + m + " : " + t);
                }
            }
            if (n > 0) {
                log("SAFEOPT OK: 已放行 setLaunchWindowingMode（" + cn + " x" + n + "）");
            } else {
                log("SAFEOPT: " + cn + " 里没有 checkPermissions，类名可能变了");
            }
        }
    }

    /* ---------------- v15：屏蔽目标 App 的全屏首屏窗口 ---------------- */

    /** 直接以 PiP 启动时，系统仍会按主题先糊一个全屏 starting window，
     *  这是"先全屏再缩小"的另一半来源。这里对目标包直接跳过。
     *  只对 sTargetPkg 生效，别的一概不动。 */
    private static volatile long sLastSplashLog = 0L;

    private static void hookSuppressSplash(final ClassLoader cl) {
        try {
            final Class<?> ar = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", cl);
            int n = 0;
            for (Method m : ar.getDeclaredMethods()) {
                if (!m.getName().equals("showStartingWindow")) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object pkg = XposedHelpers.getObjectField(param.thisObject, "packageName");
                                if (pkg == null) return;
                                String p = pkg.toString();
                                // v24：原来只认 sTargetPkg（广播上报的那一个），一旦上报没到
                                // 就完全不生效。这里把"当前 PiP 主人"也作为判据，双条件任一命中即可。
                                // 命中范围扩大到"resize 触发的重建"——那正是拖动时黑屏+图标的来源。
                                boolean hit = (sTargetPkg != null && sTargetPkg.length() > 0
                                                && sTargetPkg.equals(p))
                                        || (sPipOwnerPkg != null && sPipOwnerPkg.length() > 0
                                                && sPipOwnerPkg.equals(p));
                                if (hit) {
                                    param.setResult(false);
                                    long now = System.currentTimeMillis();
                                    if (now - sLastSplashLog > 3000) {   // 别刷屏
                                        sLastSplashLog = now;
                                        log("SPLASH 已屏蔽起始窗口: " + p);
                                    }
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignore) {
                }
            }
            log(n > 0 ? "SPLASH OK: 已屏蔽全屏首屏 x" + n + "（目标 " + sTargetPkg + "）"
                      : "SPLASH none: ActivityRecord 里没有 showStartingWindow");
        } catch (Throwable t) {
            log("SPLASH failed: " + t);
        }
    }

    /* ---------------- v12：进 PiP 后把前台还给 HDMI 源（消黑屏）---------------- */

    /** 读开关文件：空/不存在=默认；"0"=关；其它=开 */
    private static boolean flagOn(File f, boolean def) {
        try {
            String v = readTrim(f);
            if (v == null || v.length() == 0) return def;
            return !"0".equals(v);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * PiP 一旦生效，前台任务会退给"上一个全屏任务"——也就是小米桌面，
     * 而桌面不渲染 HDMI，于是 Windows 那边信号中断重同步，就是用户看到的"黑一下"。
     * 这里在 PiP 生效后立刻把 HDMI 直通源拉回前台，让这条链路根本不发生。
     *
     * v17：入参改成 Context —— 退出路径上 Activity 正在销毁，
     *      用它 startActivity 会抛异常；改用 application context + NEW_TASK。
     */
    private static void restoreHdmiFront(final Context ctx, final int attempt) {
        try {
            if (!flagOn(F_HOME_ON, true)) {
                log("hdmi-front: 已关闭（写 1 到 floatwin_home_restore 打开）");
                return;
            }
            int flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
            if (flagOn(F_NOANIM, true)) flags |= Intent.FLAG_ACTIVITY_NO_ANIMATION;

            // ① 用户显式指定了组件 → 直接用
            String cmp = readTrim(F_HOME_CMP);
            if (cmp != null && cmp.indexOf('/') > 0) {
                try {
                    Intent it = new Intent();
                    it.setComponent(android.content.ComponentName.unflattenFromString(cmp));
                    it.setFlags(flags);
                    ctx.startActivity(it);
                    log("hdmi-front: 显式组件 -> " + cmp);
                    return;
                } catch (Throwable t) {
                    log("hdmi-front 显式组件失败: " + t);
                }
            }

            // ② 用 Android 标准 TvInput 框架找 HDMI 直通输入（不需要知道任何私有类名）
            Object svc = ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (!(svc instanceof android.media.tv.TvInputManager)) {
                log("hdmi-front: TvInputManager 不可用");
                return;
            }
            android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager) svc;
            java.util.List<android.media.tv.TvInputInfo> infos = tim.getTvInputList();
            if (infos == null || infos.isEmpty()) {
                log("hdmi-front: 输入源列表为空");
                return;
            }

            String forced = readTrim(F_HOME_ID);
            String picked = null;
            StringBuilder sb = new StringBuilder();
            for (android.media.tv.TvInputInfo info : infos) {
                String id = info.getId();
                sb.append(" ").append(id);
                if (picked != null) continue;
                if (forced != null && forced.length() > 0) {
                    if (forced.equals(id)) picked = id;
                } else if (id != null
                        && id.toUpperCase(java.util.Locale.US).indexOf("HDMI") >= 0) {
                    picked = id;
                }
            }
            log("hdmi-front: 输入源 =" + sb);
            if (picked == null) {
                log("hdmi-front: 没找到 HDMI 直通源（可写 floatwin_hdmi_id 指定）");
                return;
            }

            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setData(android.media.tv.TvContract.buildChannelUriForPassthroughInput(picked));
            it.setFlags(flags);
            ctx.startActivity(it);
            log("hdmi-front: 已拉起 -> " + picked);
        } catch (Throwable t) {
            log("hdmi-front#" + attempt + " failed: " + t);
            if (attempt < 1 && sMain != null) {
                sMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        restoreHdmiFront(ctx, attempt + 1);
                    }
                }, 900);
            }
        }
    }

    /* ---------------- v12：启动期隐形窗口（盖住 App 首屏黑底）---------------- */

    private static void ghostOn(final Activity act) {
        try {
            if (!flagOn(F_GHOST_ON, false)) return;   // 默认关，GUI 里打开
            ghostOnForce(act);
        } catch (Throwable t) {
            log("ghost on failed: " + t);
        }
    }

    /** v21：不走开关的强制隐形。零黑屏启动（--windowingMode 2 直接进 PiP）时，
     *  App 会先按全屏渲染约 750ms 才缩到 PiP 尺寸 —— 这段全屏必须遮住。
     *  v20 之前靠 floatwin_ghost 开关，但开关默认关、文件也不存在，
     *  等于从未生效（23:00 实测用户看见了完整 750ms 全屏）。 */
    private static void ghostOnForce(final Activity act) {
        try {
            if (act == null) return;
            Window w = act.getWindow();
            if (w == null) return;
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.alpha = 0.01f;
            w.setAttributes(lp);
            // v31：光调窗口 alpha 挡不住 Flutter / 视频类 App。
            // 它们的内容画在独立的 SurfaceView 上，SurfaceView 不吃窗口 alpha ——
            // 所以 v30 之前"窗口明明透明了，用户还是看到一整屏 App 内容"。
            // 这里再把整棵 View 树藏起来：INVISIBLE 会连带把 SurfaceView 一起收掉。
            // （不用 GONE：GONE 会让部分 App 直接跳过测量，恢复时布局会错位。）
            try {
                View dec = w.getDecorView();
                if (dec != null) {
                    dec.setAlpha(0f);
                    dec.setVisibility(View.INVISIBLE);
                    hideSurfaceViews(dec);
                }
            } catch (Throwable ignore) {
            }
            // v33：一个 App 只起【一条】压制循环。
            // v32 里 ghostOnForce 被调用了三次，起了三条循环共用一个计数器，
            // tick 被三倍速消耗 → 2.2 秒就"到点"提前显形，
            // 而实测窗口要到 2.4 秒才真正缩到 PiP 尺寸（20:40:28）
            // —— 正好露出最后那 950ms 的全屏，就是用户看到的那一下。
            if (!sSuppress) {
                sSuppress = true;
                sSuppressTicks = 0;
                startSuppressLoop(act);
                log("ghost: on + 压制循环启动");
            } else {
                log("ghost: on（压制循环已在跑，不重复起）");
            }
            if (sMain != null) {
                sMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        ghostOff(act);                 // 安全阀：8 秒后无条件恢复
                    }
                }, 8000);
            }
        } catch (Throwable t) {
            log("ghost force on failed: " + t);
        }
    }

    /** v32：每 25ms 把隐形状态压回去一次，专门治 Activity.makeVisible()。 */
    private static void startSuppressLoop(final Activity act) {
        if (sMain == null) return;
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!sSuppress) return;
                    if (act == null || act.isFinishing() || act.isDestroyed()) {
                        sSuppress = false;
                        return;
                    }
                    Window w = act.getWindow();
                    if (w != null) {
                        WindowManager.LayoutParams lp = w.getAttributes();
                        if (lp.alpha > 0.02f) {
                            lp.alpha = 0.01f;
                            w.setAttributes(lp);
                        }
                        View dec = w.getDecorView();
                        if (dec != null) {
                            if (dec.getAlpha() > 0.001f) dec.setAlpha(0f);
                            if (dec.getVisibility() != View.INVISIBLE) dec.setVisibility(View.INVISIBLE);
                            hideSurfaceViews(dec);
                        }
                    }
                } catch (Throwable ignore) {
                }
                sSuppressTicks++;
                // v33：上限从 5 秒放宽到 10 秒。实测窗口最慢要 2.4 秒才缩到 PiP，
                // 5 秒看着够，但三条循环一叠加就变成 2.2 秒提前收工了。
                if (sSuppress && sSuppressTicks < 400) {
                    sMain.postDelayed(this, 25);
                } else if (sSuppress) {
                    sSuppress = false;
                    log("ghost：压制到点了，无条件显形");
                    ghostOff(act);
                }
            }
        }, 25);
    }

    /** v32：把 View 树里的 SurfaceView / TextureView 一并藏掉。
     *  窗口 alpha 对独立 Surface 无效，所以除了 decorView 整体 INVISIBLE，
     *  再单独给这些"自己开了一块 Surface"的 View 上一道锁。 */
    private static void hideSurfaceViews(View root) {
        try {
            if (root instanceof android.view.SurfaceView || root instanceof android.view.TextureView) {
                if (root.getVisibility() != View.INVISIBLE) root.setVisibility(View.INVISIBLE);
                return;
            }
            String cn = root.getClass().getName();
            if (cn.indexOf("Surface") >= 0 || cn.indexOf("Texture") >= 0
                    || cn.indexOf("Flutter") >= 0) {
                if (root.getVisibility() != View.INVISIBLE) root.setVisibility(View.INVISIBLE);
                return;
            }
            if (root instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) hideSurfaceViews(g.getChildAt(i));
            }
        } catch (Throwable ignore) {
        }
    }

    /** v32：显形时把 hideSurfaceViews 动过的 View 全部还原 */
    private static void showSurfaceViews(View root) {
        try {
            if (root instanceof android.view.SurfaceView || root instanceof android.view.TextureView) {
                if (root.getVisibility() != View.VISIBLE) root.setVisibility(View.VISIBLE);
                return;
            }
            String cn = root.getClass().getName();
            if (cn.indexOf("Surface") >= 0 || cn.indexOf("Texture") >= 0
                    || cn.indexOf("Flutter") >= 0) {
                if (root.getVisibility() != View.VISIBLE) root.setVisibility(View.VISIBLE);
                return;
            }
            if (root instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) showSurfaceViews(g.getChildAt(i));
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * v30：这个 Activity 允不允许"启动期先隐形"。
     *
     * 隐形是为了盖住进 PiP 之前那段全屏渲染，但【桌面和 HDMI 直通源要是透明了，
     * 电视就真的黑掉了】—— 所以这几类一律不碰。
     * （模块对任意 App 都生效，桌面/tvplayer 每次 onResume 也会走到这里，必须挡住。）
     */
    private static boolean ghostCandidate(Activity act) {
        try {
            String pn = act.getPackageName();
            if (pn == null || pn.length() == 0) return false;
            String p = pn.toLowerCase(java.util.Locale.US);
            if ("android".equals(p) || p.startsWith("com.android.")) return false;
            if (p.contains("systemui") || p.contains("tvinput") || p.contains("tvplayer")) return false;
            if (p.contains("tvhome") || p.contains("launcher") || p.contains("orchard")) return false;
            if (p.contains("mitv.tv")) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void ghostOff(Activity act) {
        try {
            sSuppress = false;                 // v32：停掉压制循环，否则刚显形又被压回去
            Window w = act.getWindow();
            if (w == null) return;
            // v31：装饰树也要恢复可见（SurfaceView 是藏在这层里的）。
            // 注意不能再用"alpha 已接近 1 就直接返回"做短路 —— 那样窗口 alpha 被别处
            // 恢复过、但 View 树还是 INVISIBLE 时，App 内容就永远出不来。
            try {
                View dec = w.getDecorView();
                if (dec != null) {
                    showSurfaceViews(dec);     // v32：先解 SurfaceView，再解整棵树
                    if (dec.getVisibility() != View.VISIBLE) dec.setVisibility(View.VISIBLE);
                    if (dec.getAlpha() < 0.999f) dec.setAlpha(1f);
                }
            } catch (Throwable ignore) {
            }
            WindowManager.LayoutParams lp = w.getAttributes();
            if (lp.alpha < 0.999f) {
                lp.alpha = 1f;
                w.setAttributes(lp);
            }
            log("ghost: off");
        } catch (Throwable ignore) {
        }
    }

    /* ---------------- 客户端：进 PiP + density 覆盖 ---------------- */

    /** v16：进 PiP 前向 system_server 抢位 —— 顺带让上一个占着位子的 App 让开。
     *  不这么做的话，系统会把旧 App 从 pinned 踢成 fullscreen，
     *  结果就是"一个全屏 + 一个悬浮"，而且全屏那个会把 HDMI 画面顶掉。 */
    private static void requestPipSlot(final Activity act) {
        try {
            Intent it = new Intent(PIPREQ_ACTION);
            it.putExtra("pkg", act.getPackageName());
            it.addFlags(0x10000000);
            act.sendBroadcast(it);
        } catch (Throwable t) {
            log("PIPREQ send fail: " + t);
        }
    }

    private static void tryEnterPip(final Activity act, final int attempt) {
        try {
            if (act.isFinishing()) return;
            if (android.os.Build.VERSION.SDK_INT < 26) return;
            if (act.isInPictureInPictureMode()) return;

            patchActivityInfo(act);

            applyOrientation(act);

            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
            try {
                int[] sz = readPipSize();
                if (sz != null) {
                    b.setAspectRatio(new Rational(sz[0], sz[1]));
                } else {
                    b.setAspectRatio(readPipOrient() == 1 ? new Rational(9, 16) : new Rational(16, 9));
                }
            } catch (Throwable ignore) {
            }
            boolean ok = act.enterPictureInPictureMode(b.build());
            log("enterPip attempt#" + attempt + " -> " + ok + " : " + act.getClass().getName());
        } catch (Throwable t) {
            log("enterPip#" + attempt + " failed: " + t);
            if (attempt < 2 && sMain != null) {
                sMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        tryEnterPip(act, attempt + 1);
                    }
                }, attempt == 0 ? 600 : 1500);
            }
        }
    }

    private static void onPipChanged(final Activity act, final boolean inPip) {
        if (act == null || sMain == null) return;
        if (inPip) {
            PIPPED.put(act, Boolean.TRUE);
            onPipEnteredSetup(act, true);
        } else {
            ghostOff(act);
            markPipInactive();
            restoreDensity(act);
            // v17：这里是"退出悬浮窗但 Activity 还没销毁"的情况（例如被别的 App 顶掉）。
            // 同样要把前台还给 HDMI，否则电视停在桌面 → 显示器黑屏。
            if (PIPPED.containsKey(act)) {
                PIPPED.remove(act);
                sPipExitTs = System.currentTimeMillis();
                log("PiP 已退出（活动仍在）→ 先收回后台再还原 HDMI，避免全屏那一下黑屏");
                final Context ctx = act.getApplicationContext();
                try {
                    act.moveTaskToBack(true);
                } catch (Throwable ignore) {
                }
                if (ctx != null) {
                    sMain.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            restoreHdmiFront(ctx, 0);
                        }
                    }, 250);
                }
            }
        }
    }

    /** 进入 PiP 之后统一要做的事：恢复不透明、登记遥控、按方向改窗口比例、覆盖 density。
     *  restoreFront=false 用于「直接以 PiP 启动」——此时 HDMI 从来没被 pause 过，
     *  再去拉一次前台反而可能触发重协商（黑屏），所以跳过。 */
    private static void onPipEnteredSetup(final Activity act, final boolean restoreFront) {
        if (act == null || sMain == null) return;
        PIPPED.put(act, Boolean.TRUE);   // v17：标记"这个 Activity 真的进过 PiP"
        sPipAct = act;
        sHidden = false;                 // v23：重新进场 → 解除隐身
        fadeIn(act);                     // 并恢复不透明 + 收回焦点
        setupKeySink(act);
        applyOrientation(act);
        applyPipAspect(act);
        startRatioDaemon();              // v20：悬浮期间持续把 aspectRatio 同步成目标尺寸的比例
        if (restoreFront) {
            // v30：这里原本是"立刻显形"，但 enterPip 只是把 task 标成 pinned，
            // 窗口真正从 1920x1080 缩到 PiP 尺寸还要几百毫秒 —— 立刻显形就等于又闪一下全屏。
            // 改成和 direct-pinned 一样：等外框就位再显形。
            waitForPipBounds(act, 0);
            sMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyDensity(act, 0);
                }
            }, 600);
            sMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    restoreHdmiFront(act, 0);   // v12：前台还给 HDMI，消黑屏
                }
            }, 300);
        } else {
            // v15：直接以 PiP 启动时，窗口是"先按 1920x1080 建、约 2 秒后才缩到
            // 319x566"的，这段时间只要窗口不透明，用户看到的就是全屏闪一下。
            // 所以这里不能马上显形，要等外框真的落到 PiP 尺寸。
            waitForPipBounds(act, 0);
        }
    }

    /** v20：悬浮期间持续把系统的 aspectRatio 同步成目标尺寸的比例。
     *  这才是实时缩放的关键：日志证实 task 的 PictureInPictureParams.aspectRatio 是
     *  进 PiP 时定死的（415/961），之后无论 LIVE_HOOK 怎么改 getBounds 的返回值，
     *  TvPipBoundsAlgorithm 都会按旧比例把尺寸重算回去 —— 表现就是
     *  "拖动时闪一下目标尺寸，立刻缩回原样"。setPictureInPictureParams 是官方
     *  运行时改比例的通道，改完系统自己就会按新比例算 bounds。 */
    private static volatile boolean sRatioDaemonStarted = false;
    private static volatile long sSizeSettleTs = 0;      // v22：最后一次尺寸变化的时刻
    private static volatile boolean sSizeFaded = false;  // v22：当前是否因 resize 而透明

    /**
     * v27：这个线程【已经停用】，只留一行说明。
     *
     * 它原来干的事是：尺寸一变就 setPictureInPictureParams(setAspectRatio(w/h))。
     * 这是 Android 唯一"官方"的运行时改尺寸通道，但它有两个致命副作用：
     *   ① aspectRatio 变化 → 配置变更 → Activity 重建 → 系统给 App 糊一层起始窗口
     *      （黑底 + App 图标）→ 重建完才恢复，就是"闪一下"；
     *   ② v22 为了遮住 ① 又加了 fadeOut（alpha=0），结果遮不住系统的起始窗口，
     *      反而让自己变成一块"透明的黑色阴影" —— 用户看到的正是这个。
     *
     * 现在缩放和拖动走的是同一条链：live daemon 直接用
     * TvPipBoundsController.applyPlacementBounds(0, Rect) 改窗口矩形，
     * 只动窗口、不动配置 → 不重建、不闪、也不需要透明遮掩，完全实时。
     * （拖动已经验证过是实时的，缩放本来就该用同一条路。）
     */
    private static void startRatioDaemon() {
        if (sRatioDaemonStarted) return;
        sRatioDaemonStarted = true;
        log("ratio daemon：已停用（缩放统一走 applyPlacementBounds 实时摆放，不再改 aspectRatio）");
    }

    private static void waitForPipBounds(final Activity act, final int attempt) {
        if (act == null || sMain == null) return;
        // v34：上限 4s → 8s。bettbox 那次实测窗口要到 ~5.5s 才缩成悬浮窗，
        // 4 秒一到就"强制显形"，正好把最后那 1.5 秒的全屏露给用户看。
        if (attempt > 200) {                    // v34：40ms 一步 → 安全阀约 8 秒
            log("waitPipBounds: 超时，强制显形");
            applyDensity(act, 0);
            ghostOff(act);
            return;
        }
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
                    int w = decor == null ? 0 : decor.getWidth();
                    int h = decor == null ? 0 : decor.getHeight();
                    // v30：全屏是 1920x1080；判据放宽到"宽高都明显小于全屏"，
                    // 免得用户把悬浮窗拉得比较大（>1200px）时永远等不到就位、白白多隐形 4 秒。
                    if (w > 0 && h > 0 && w < 1700 && h < 950) {
                        log("waitPipBounds: 外框已就位 " + w + "x" + h + "px（第 "
                                + attempt + " 次，约 " + (attempt * 40) + "ms）");
                        applyDensity(act, 0);
                        sMain.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                ghostOff(act);          // 密度算完再显形，避免看到错位布局
                            }
                        }, 150);
                        return;
                    }
                } catch (Throwable ignore) {
                }
                waitForPipBounds(act, attempt + 1);
            }
        }, 40);                                  // v32：150ms → 40ms，早 3 倍发现就位
    }

    /** 按 floatwin_pip_orient 强制方向：竖屏(1) 才会让窗口真的是竖的 */
    private static void applyOrientation(Activity act) {
        try {
            final int o = readPipOrient();
            if (o == 0) return;
            if (act.getRequestedOrientation() == o) return;
            act.setRequestedOrientation(o);
            log("requestedOrientation -> " + o);
        } catch (Throwable t) {
            log("setRequestedOrientation failed: " + t);
        }
    }

    /** 窗口外框比例：显式 pip_size 优先，否则竖屏 9:16、横屏 16:9。
     *  这一条决定 PiP 到底是竖条还是横条（之前写死 16:9，所以永远是横的）。 */
    private static void applyPipAspect(Activity act) {
        if (android.os.Build.VERSION.SDK_INT < 26 || act == null) return;
        try {
            int[] sz = readPipSize();
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
            if (sz != null) {
                b.setAspectRatio(new Rational(sz[0], sz[1]));
                log("pip aspect -> 显式 " + sz[0] + "x" + sz[1]);
            } else {
                boolean portrait = (readPipOrient() == 1);
                b.setAspectRatio(portrait ? new Rational(9, 16) : new Rational(16, 9));
                log("pip aspect -> " + (portrait ? "9:16(竖)" : "16:9(横)"));
            }
            act.setPictureInPictureParams(b.build());
        } catch (Throwable t) {
            log("setPictureInPictureParams failed: " + t);
        }
    }

    private static void applyDensity(final Activity act, final int attempt) {
        try {
            View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            if (decor == null) return;
            int px = decor.getWidth();

            if (px <= 0) {
                if (attempt < 3 && sMain != null) {
                    sMain.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            applyDensity(act, attempt + 1);
                        }
                    }, 400);
                }
                return;
            }

            Integer lastPx = LAST_PX.get(act);
            if (lastPx != null && lastPx.intValue() == px) {
                if (attempt < 3 && sMain != null) {
                    sMain.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            applyDensity(act, attempt + 1);
                        }
                    }, 1500);
                }
                return;
            }

            Resources res = act.getResources();
            int origDpi;
            if (!ORIG_DPI.containsKey(act)) {
                origDpi = res.getConfiguration().densityDpi;
                ORIG_DPI.put(act, Integer.valueOf(origDpi));
            } else {
                origDpi = ORIG_DPI.get(act).intValue();
            }

            int targetDp = readTargetDp(isPhone() ? DP_PHONE : DP_TV);
            int newDpi = Math.round(px * 160f / targetDp);
            if (newDpi < 72) newDpi = 72;
            int cap = origDpi > 320 ? origDpi : 320;
            if (newDpi > cap) newDpi = cap;

            Configuration c = new Configuration(res.getConfiguration());
            c.densityDpi = newDpi;
            // v6 关键修复：screenWidthDp/HeightDp 必须跟 density 一起改，
            // 否则 Configuration 自相矛盾（读 screenWidthDp 的库、按 -sw<N>dp 选资源的
            // 资源桶都还按老值走）→ 那就是"还是显示不全"的元凶。
            int ph = decor.getHeight();
            c.screenWidthDp = targetDp;
            if (ph > 0) c.screenHeightDp = Math.round(ph * 160f / newDpi);
            // smallestScreenWidthDp 决定 App 选哪个资源桶（sw360dp 手机 / sw720dp 电视）。
            // v6 故意不动它（怕掉进手机布局）；v7 的 phone 预设就是要它掉进手机布局。
            int sw = readPipSw();
            if (sw > 0) {
                c.smallestScreenWidthDp = sw;
            } else if (isPhone()) {
                c.smallestScreenWidthDp = Math.min(c.screenWidthDp, c.screenHeightDp);
            }

            res.updateConfiguration(c, res.getDisplayMetrics());
            // 有些库拿的是 Application/BaseContext 的 Resources，一并改掉
            try {
                android.app.Application app = act.getApplication();
                if (app != null) {
                    Resources ar = app.getResources();
                    if (ar != res) ar.updateConfiguration(c, ar.getDisplayMetrics());
                }
            } catch (Throwable ignore) {
            }
            LAST_PX.put(act, Integer.valueOf(px));
            decor.requestLayout();
            decor.invalidate();

            Configuration after = res.getConfiguration();
            log("density[" + readPipPreset() + "] " + origDpi + " -> " + newDpi + "dpi (窗口 " + px + "x" + ph
                    + "px, 目标 " + targetDp + "dp, 第 " + attempt + " 次)");
            log("  生效校验: densityDpi=" + after.densityDpi
                    + " screenWidthDp=" + after.screenWidthDp
                    + " screenHeightDp=" + after.screenHeightDp
                    + " smallestSwDp=" + after.smallestScreenWidthDp
                    + " => 逻辑 " + logicalW(after) + "x" + logicalH(after, decor) + "dp");

            tryFocusPip(act);

            if (attempt < 3 && sMain != null) {
                sMain.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        applyDensity(act, attempt + 1);
                    }
                }, 1500);
            }
        } catch (Throwable t) {
            log("applyDensity failed: " + t);
        }
    }

    private static int logicalW(Configuration c) {
        if (c.densityDpi <= 0) return -1;
        return c.screenWidthDp;
    }

    private static int logicalH(Configuration c, View decor) {
        if (c.densityDpi <= 0) return -1;
        return Math.round(decor.getHeight() * 160f / c.densityDpi);
    }

    /**
     * 实验性：尝试让 PiP 窗口能收到遥控器按键。
     * PiP 默认带 FLAG_NOT_FOCUSABLE（系统加的），所以按键都给后面的全屏 App。
     * 这里先把它清掉再申请焦点，并把 flags 打出来供判断。
     */
    private static void tryFocusPip(Activity act) {
        try {
            Window w = act.getWindow();
            if (w == null) return;
            int flags = w.getAttributes().flags;
            final int NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            final int NOT_TOUCH_MODAL = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            log("window flags=0x" + Integer.toHexString(flags)
                    + " NOT_FOCUSABLE=" + ((flags & NOT_FOCUSABLE) != 0)
                    + " NOT_TOUCH_MODAL=" + ((flags & NOT_TOUCH_MODAL) != 0));

            if (!focusWanted()) {
                log("  焦点实验未开启（要试就 echo 1 > /data/local/tmp/floatwin_pip_focus）");
                return;
            }
            try {
                w.clearFlags(NOT_FOCUSABLE);
                w.addFlags(NOT_TOUCH_MODAL);
            } catch (Throwable t) {
                log("  clearFlags failed: " + t);
            }
            View d = w.getDecorView();
            try {
                d.setFocusable(true);
                d.setFocusableInTouchMode(true);
                d.requestFocus();
            } catch (Throwable ignore) {
            }
            log("  焦点实验已执行, flags now=0x"
                    + Integer.toHexString(w.getAttributes().flags));
        } catch (Throwable t) {
            log("tryFocusPip failed: " + t);
        }
    }

    private static void restoreDensity(Activity act) {
        try {
            LAST_PX.remove(act);
            Integer orig = ORIG_DPI.remove(act);
            if (orig == null) return;
            Resources res = act.getResources();
            Configuration c = new Configuration(res.getConfiguration());
            c.densityDpi = orig.intValue();
            res.updateConfiguration(c, res.getDisplayMetrics());
            View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            if (decor != null) decor.requestLayout();
            log("density restored to " + orig);
        } catch (Throwable t) {
            log("restoreDensity failed: " + t);
        }
    }

    /* ---------------- 运行时可调参数 ---------------- */

    private static int readInt(File f, int min, int max, int def) {
        if (!f.exists()) return def;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            if (s != null) {
                int v = Integer.parseInt(s.trim());
                if (v >= min && v <= max) return v;
                log("忽略越界值 " + v + "（允许 " + min + "~" + max + "）来自 " + f.getPath());
            }
        } catch (Throwable ignore) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignore) {}
        }
        return def;
    }

    private static int readTargetDp(int def) {
        return readInt(new File("/data/local/tmp/floatwin_target_dp"), 240, 1920, def);
    }

    private static int readPipScale() {
        return readInt(new File("/data/local/tmp/floatwin_pip_scale"), 100, 300, DEFAULT_PIP_SCALE);
    }

    /** "tr" / "tl" / "br" / "bl" / "c" / "x,y"（绝对像素坐标） */
    private static String readPipPos() {
        File f = new File("/data/local/tmp/floatwin_pip_pos");
        if (!f.exists()) return DEFAULT_PIP_POS;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            if (s != null) {
                s = s.trim().toLowerCase();
                if (s.length() > 0) return s;
            }
        } catch (Throwable ignore) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignore) {}
        }
        return DEFAULT_PIP_POS;
    }

    /** 解析 "120,60" 或 v18 的 "120,60,640,360"（带尺寸=实时缩放）；
     *  不是则返回 null。返回恒为 4 个：{x, y, w, h}，没写尺寸时 w/h = -1 */
    private static int[] parseXY(String pos) {
        if (pos == null) return null;
        String[] p = pos.split(",");
        if (p.length < 2) return null;
        try {
            int x = Integer.parseInt(p[0].trim());
            int y = Integer.parseInt(p[1].trim());
            int w = -1, h = -1;
            if (p.length >= 4) {
                w = Integer.parseInt(p[2].trim());
                h = Integer.parseInt(p[3].trim());
            }
            return new int[]{x, y, w, h};
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** 是否尝试让 PiP 窗口抢焦点（实验性） */
    private static boolean focusWanted() {
        return readInt(new File("/data/local/tmp/floatwin_pip_focus"), 0, 1, 0) == 1;
    }

    /* ---- v7：竖屏手机预设 ---- */

    /** "tv"(默认，横屏电视布局) / "phone"(竖屏手机布局) */
    private static String readPipPreset() {
        File f = new File("/data/local/tmp/floatwin_pip_preset");
        if (!f.exists()) return "tv";
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            if (s != null) {
                s = s.trim().toLowerCase();
                if (s.length() > 0) return s;
            }
        } catch (Throwable ignore) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignore) {}
        }
        return "tv";
    }

    private static boolean isPhone() {
        return "phone".equals(readPipPreset());
    }

    /** "480x854" 形式的显式窗口像素尺寸，优先级最高；没有则 null */
    private static int[] readPipSize() {
        File f = new File("/data/local/tmp/floatwin_pip_size");
        if (!f.exists()) return null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            if (s != null) {
                s = s.trim().toLowerCase();
                int i = s.indexOf('x');
                if (i <= 0) return null;
                int w = Integer.parseInt(s.substring(0, i).trim());
                int h = Integer.parseInt(s.substring(i + 1).trim());
                if (w > 0 && h > 0) return new int[]{w, h};
            }
        } catch (Throwable ignore) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignore) {}
        }
        return null;
    }

    /** smallestScreenWidthDp 覆盖：0=不覆盖；>0=强制（决定 App 选手机布局还是电视布局） */
    private static int readPipSw() {
        return readInt(new File("/data/local/tmp/floatwin_pip_sw"), 0, 2000, 0);
    }

    /** 强制 Activity 方向：0=不动；1=竖屏；4=随传感器；10=全传感器；-1=不指定 */
    private static int readPipOrient() {
        return readInt(new File("/data/local/tmp/floatwin_pip_orient"), -1, 10, 0);
    }

    /* ---------------- 杂项 ---------------- */

    private static void patchActivityInfo(Activity act) {
        try {
            Field f = Activity.class.getDeclaredField("mActivityInfo");
            f.setAccessible(true);
            Object o = f.get(act);
            if (o instanceof ActivityInfo) {
                ActivityInfo ai = (ActivityInfo) o;
                ai.flags |= FLAG_PIP;
                try {
                    Field rm = ActivityInfo.class.getField("resizeMode");
                    rm.setInt(ai, 2);
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            log("patchActivityInfo failed: " + t);
        }
    }

    private static void log(String s) {
        try {
            XposedBridge.log(TAG + s);
            Log.i("FloatWin", s);
        } catch (Throwable ignore) {
        }
    }
}
