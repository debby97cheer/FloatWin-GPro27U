#!/system/bin/sh
# FloatWin 返回键守护（电视端自闭环，不依赖电脑）
#
# 作用：常驻监听模块打出的 "BACKKEY <包名>" 日志，
#       收到就执行 am force-stop —— 等价于控制台上的「强制停止」，
#       实测不黑屏、不回桌面、悬浮窗直接消失。
#
# 为什么不在 App 里自杀：App 自己 finish/kill 会让系统抬一个 task 上来（抬的就是桌面），
# 而且带前台服务的 App 杀完还会被系统拉活。
# 为什么不在电脑上做：控制台必须一直开着才行，一关就失效。
#
# 由控制台写入 /data/local/tmp/ 并启动。电视重启后需要再启动一次（控制台上点一下即可）。
# 停止：kill $(pgrep -f floatwin_backd)

LOG=/data/local/tmp/floatwin_backd.log
echo "---- start $(date +%H:%M:%S) pid=$$ ----" >> "$LOG"

while : ; do
    # -T 1：只从"最后 1 条"开始跟，避免启动时把历史日志倒出来误触发
    logcat -v raw -s FloatWin:I -T 1 2>/dev/null | while IFS= read -r line ; do
        case "$line" in
            *BACKKEY*)
                p=${line##*BACKKEY}
                set -- $p          # 去掉首尾空格
                p=$1
                if [ -n "$p" ] ; then
                    am force-stop "$p"
                    echo "$(date +%H:%M:%S) force-stop $p" >> "$LOG"
                fi
                ;;
        esac
    done
    sleep 1
done
