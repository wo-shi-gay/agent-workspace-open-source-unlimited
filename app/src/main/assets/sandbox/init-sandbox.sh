#!/system/bin/sh
# PRoot sandbox launcher (mirrors the reference APK layout / aarch64).
# Usage:
#   init-sandbox.sh <PREFIX> <ROOTFS_DIR> <NATIVE_LIB_DIR> [<PROJECT_DIR>] [cmd...]
#   PREFIX         writable scratch dir (must live under files/: tmp + public)
#   ROOTFS_DIR     extracted Debian rootfs
#   NATIVE_LIB_DIR app nativeLibraryDir: executable location that already holds
#                  libproot.so, libproot-xed.so, libtalloc.so, libtalloc.so.2
#                  shipped via jniLibs. We exec proot out of there because files/
#                  is frequently mounted non-executable on Android.
set -u
PREFIX="$1"
ROOTFS_DIR="$2"
NATIVE_LIB_DIR="$3"
PROJECT_DIR="${4:-/nonexistent}"
shift 4 || shift $(( $# )) 2>/dev/null || true

# Libraries (incl. talloc under its soname) all live in the native dir; no copy
# into the possibly non-exec PREFIX is needed.
export LD_LIBRARY_PATH="$NATIVE_LIB_DIR"
export PROOT_TMP_DIR="$PREFIX/tmp"
mkdir -p "$PREFIX/tmp" "$ROOTFS_DIR/tmp" "$PREFIX/public" "$PREFIX/tmp/proot"

# ⚠⚠ 2026-11-13 修复 "can't create temporary file /data/local/shmXXXX.tmp:
# Permission denied"：
#   proot 的 --link2symlink / 内部 shm 会在 TMPDIR 下 mktemp。Android 上
#   /data/local 属于 shell 用户，App 无写权限；而 proot 默认兜底就爱往那扔。
#   必须显式把所有 tmp 类环境变量指到 App 私有目录（$PREFIX/tmp）。
export TMPDIR="$PREFIX/tmp"
export TMP="$PREFIX/tmp"
export TEMP="$PREFIX/tmp"
export PROOT_TMP_DIR="$PREFIX/tmp"

# If nativeLibraryDir lacks the talloc symlink name, alias it inside PREFIX as a
# last-resort (kept for unusual devices; native dir normally already has it).
if [ ! -f "$NATIVE_LIB_DIR/libtalloc.so.2" ] && [ -f "$NATIVE_LIB_DIR/libtalloc.so" ]; then
  cp "$NATIVE_LIB_DIR/libtalloc.so" "$NATIVE_LIB_DIR/libtalloc.so.2" 2>/dev/null || cp "$NATIVE_LIB_DIR/libtalloc.so" "$PREFIX/libtalloc.so.2"
  export LD_LIBRARY_PATH="$PREFIX:$NATIVE_LIB_DIR"
fi

# Pick a shell that actually exists AND is executable inside the rootfs.
#
# ⚠⚠ 2026-11-13 Alpine 专项修复（报错 "-c: applet not found"）：
#
# Alpine 的 /bin/sh 是**指向 /bin/busybox 的软链**。proot 为了加载真正的
# ELF 会解析软链，于是 argv[0] 变成 /bin/busybox。busybox 靠 argv[0] 的
# basename 决定跑哪个 applet —— basename 是 "busybox" 而不是 "sh"，它就把
# 第一个参数 "-c" 当成 applet 名去查，报 "-c: applet not found"。
#
# Debian 从来不炸：它的 /bin/sh → dash，dash 不做 applet 分发，argv[0] 是
# 什么都无所谓。所以同一个写法在 Debian 下一直正常，只有 Alpine 中招。
#
# 修法：busybox 必须**显式带 applet 名**调用 —— `busybox sh -c '...'`。
# 因此 busybox 要排在候选列表最前面，让 Alpine 优先命中它。
ROOTFS_SHELL=
SHELL_APP=
SHELL_ZERO=
for candidate in /bin/busybox /usr/bin/busybox /usr/bin/sh /bin/sh /usr/bin/dash /bin/dash /bin/bash /usr/bin/bash; do
  if [ -x "$ROOTFS_DIR$candidate" ]; then
    ROOTFS_SHELL=$candidate
    case "$candidate" in
      *busybox)
        # busybox 要传 applet 名；交互分支的 $0 也用 "sh"，
        # 这样脚本里的 `exec "$0"` 才能重新拉起一个 shell
        # （若传 /bin/busybox，exec 后无参数会直接打印 usage）
        SHELL_APP="sh"
        SHELL_ZERO="sh"
        ;;
      *)
        SHELL_APP=""
        SHELL_ZERO="$ROOTFS_SHELL"
        ;;
    esac
    break
  fi
done
if [ -z "$ROOTFS_SHELL" ]; then
  # 2026-11-13：以前只报这一句，看不到 rootfs 到底什么样，只能靠猜。
  # 现在把现场列出来（目录是否存在、bin/ 下有什么、是不是软链指错地方）。
  echo "init-sandbox: no executable shell found in rootfs" >&2
  echo "init-sandbox: ROOTFS_DIR=$ROOTFS_DIR" >&2
  echo "init-sandbox: exists=$([ -e "$ROOTFS_DIR" ] && echo yes || echo no) dir=$([ -d "$ROOTFS_DIR" ] && echo yes || echo no) link=$([ -L "$ROOTFS_DIR" ] && echo yes || echo no)" >&2
  echo "init-sandbox: realpath=$(realpath "$ROOTFS_DIR" 2>/dev/null || echo FAILED)" >&2
  echo "init-sandbox: listing=$(ls -A "$ROOTFS_DIR" 2>&1 | head -20 | tr '\n' ' ')" >&2
  echo "init-sandbox: bin=$([ -e "$ROOTFS_DIR/bin" ] && ls -A "$ROOTFS_DIR/bin" 2>&1 | head -20 | tr '\n' ' ' || echo NO_BIN_DIR)" >&2
  exit 127
fi

ARGS="--kill-on-exit"
for system_mnt in /apex /odm /product /system /system_ext /vendor /linkerconfig/ld.config.txt /linkerconfig/com.android.art/ld.config.txt /plat_property_contexts /property_contexts; do
  if [ -e "$system_mnt" ]; then
    system_mnt=$(realpath "$system_mnt")
    ARGS="$ARGS -b ${system_mnt}"
  fi
done
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /sys"
# User storage: resolve the real mount path (e.g. /storage/emulated/0) so the
# bind is not defeated by the /sdcard symlink on some ROMs.
for user_mnt in /sdcard /storage; do
  if [ -e "$user_mnt" ]; then
    user_mnt=$(realpath "$user_mnt")
    ARGS="$ARGS -b ${user_mnt}"
  fi
done
ARGS="$ARGS -b $PREFIX"
# ⚠ 2026-11-17：不再把工作区绑到 /workspace（照抄 Operit 的做法）。
# 虚拟挂载点会造成两套世界：终端里是 /workspace/x.apk，而 App 进程里的原生工具
# （apk_info / dexkit_* / apk_*）只认识真实路径 .../workspaces/<id>/x.apk，
# 模型于是只能 find/ls 满盘找文件。工作区本来就在 /storage 下（已 bind），
# 这里原样 bind 真路径，终端与工具看到的就是同一个绝对路径。
if [ -d "$PROJECT_DIR" ]; then
  ARGS="$ARGS -b $PROJECT_DIR"
fi
ARGS="$ARGS -b $PREFIX/public:/public"
ARGS="$ARGS -b $PREFIX/public:/home"
ARGS="$ARGS -b $PREFIX/public:/root"
ARGS="$ARGS -b $ROOTFS_DIR/tmp:/dev/shm"
ARGS="$ARGS -r $ROOTFS_DIR"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"
# 起始目录 = 工作区真实路径（不再有 /workspace）
START_CWD="/"
if [ -d "$PROJECT_DIR" ]; then
  START_CWD="$PROJECT_DIR"
fi
ARGS="$ARGS --cwd=$START_CWD"

export PROOT_LOADER="$NATIVE_LIB_DIR/libproot.so"
export PROOT="$NATIVE_LIB_DIR/libproot-xed.so"
export PROOT_LIB_PATH="$PREFIX:$NATIVE_LIB_DIR"
export LD_LIBRARY_PATH="$PROOT_LIB_PATH:$LD_LIBRARY_PATH"

# DNS: 系统镜像可能不带 /etc/resolv.conf（Alpine minirootfs 就是），或者带一个
# 空文件。用 -s 而不是 -f：空文件同样算「没配」，必须补。
# 只列 3 条，因为 glibc / musl 的 MAXNS 都是 3，第 4 条起直接被忽略
# （连警告都没有，写了也是死重）。顺序即优先级，前一条超时才轮后一条。
# 选这三家的理由：国内 Anycast 节点、不劫持、支持 EDNS Client Subnet。
# 默认 DNS 统一为腾讯云：119.29.29.29（DNSPod Public DNS，首选）、
# 1.12.12.12（腾讯云公共 DNS）；223.5.5.5（阿里）只作第三备，防腾讯节点故障断网。
# 以前只写死 1.1.1.1，国内网络下 apk add 第一步就卡在解析上。
# 无条件写入（镜像自带 resolv.conf 时也覆盖成腾讯云），除非已经是腾讯云地址。
if ! grep -q '119\.29\.29\.29' "$ROOTFS_DIR/etc/resolv.conf" 2>/dev/null; then
  mkdir -p "$ROOTFS_DIR/etc"
  printf 'nameserver 119.29.29.29\nnameserver 1.12.12.12\nnameserver 223.5.5.5\n' \
    > "$ROOTFS_DIR/etc/resolv.conf"
fi

# ---- Alpine 专项补全（Debian 不需要这些，所以做得很轻）----
# Alpine minirootfs 是「裸」的，缺三样会让日常使用很难受：
#   ① /etc/apk/repositories  → 不补 `apk add` 直接 "unable to select packages"
#   ② /etc/os-release        → 很多脚本靠它判断发行版，缺了就当成未知系统
#   ③ /etc/profile.d 环境变量 → PATH 里缺 /usr/local/bin 之类
if [ -d "$ROOTFS_DIR/etc/apk" ]; then
  if [ ! -s "$ROOTFS_DIR/etc/apk/repositories" ]; then
    printf 'https://mirrors.aliyun.com/alpine/v3.19/main\nhttps://mirrors.aliyun.com/alpine/v3.19/community\n' \
      > "$ROOTFS_DIR/etc/apk/repositories"
  fi
  if [ ! -s "$ROOTFS_DIR/etc/os-release" ]; then
    cat > "$ROOTFS_DIR/etc/os-release" <<'OSREL'
NAME="Alpine Linux"
ID=alpine
VERSION_ID=3.19.0
PRETTY_NAME="Alpine Linux v3.19"
HOME_URL="https://alpinelinux.org/"
BUG_REPORT_URL="https://gitlab.alpinelinux.org/alpine/aports/-/issues"
OSREL
  fi
  # busybox 的默认 PATH 不含 /usr/local/bin，补上（和 Debian 分支保持一致）
  if [ ! -f "$ROOTFS_DIR/etc/profile.d/agentpad-path.sh" ]; then
    mkdir -p "$ROOTFS_DIR/etc/profile.d"
    printf 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n' \
      > "$ROOTFS_DIR/etc/profile.d/agentpad-path.sh"
  fi
fi
# ---- 主机名 + 欢迎横幅（Debian / Alpine 通用）----
# 主机名统一成 agent-workspace：以前 rootfs 里是什么就用什么（Debian 是随机的
# 十六进制、Alpine 干脆没有），终端提示符和 hostname 命令输出很难看。
# 横幅写到 /etc/agentpad-banner.txt，交互 shell 启动时 cat 出来。
# 用带引号的 heredoc（<<'BANNER'）→ 内容完全字面，反斜杠和 $ 都不被展开。
cat > "$ROOTFS_DIR/etc/agentpad-banner.txt" <<'BANNER'
 __        __         _                            _
\ \      / /__  _ __| | __ _ ___ _ __   ___  ___| | __  ___
 \ \ /\ / / _ \| '__| |/ _` / __| '_ \ / _ \/ __| |/ / / __|
  \ V  V / (_) | |  | | (_| \__ \ |_) |  __/ (__|   <  \__ \
   \_/\_/ \___/|_|  |_|\__,_|___/ .__/ \___|\___|_|\_\ |___/
                                |_|
BANNER

echo "agent-workspace" > "$ROOTFS_DIR/etc/hostname"

# /etc/hosts 里也映射一份，否则有些工具解析不了自己的主机名
touch "$ROOTFS_DIR/etc/hosts"
if ! grep -q "agent-workspace" "$ROOTFS_DIR/etc/hosts" 2>/dev/null; then
  printf '127.0.0.1\tagent-workspace\n::1\t\tagent-workspace\n' >> "$ROOTFS_DIR/etc/hosts"
fi

if [ ! -f "$ROOTFS_DIR/linkerconfig/ld.config.txt" ]; then
  mkdir -p "$ROOTFS_DIR/linkerconfig"
  touch "$ROOTFS_DIR/linkerconfig/ld.config.txt"
fi

# Persist the bundled JRE's lib path into every shell session (libjli.so lives
# in /usr/local/jre17/lib; without this `java` fails with
# "libjli.so: cannot open shared object file"). Empty if JRE not installed.
JRE_LIB=""
if [ -d "$ROOTFS_DIR/usr/local/jre17/lib" ]; then
  JRE_LIB="/usr/local/jre17/lib"
fi

# busybox 的登录/交互 shell 只在设置了 ENV 时才 source /etc/profile
# （dash/bash 靠 argv[0] 带 '-' 或 -l 参数，busybox 不看这些）。
# 不设 ENV 的话上面补的 profile.d/agentpad-path.sh 永远不会被执行。
if [ -n "$SHELL_APP" ]; then
  export ENV=/etc/profile
fi

if [ $# -eq 0 ]; then
  # 交互 shell：$0 用 SHELL_ZERO。busybox 下必须是 "sh" 而不是 /bin/busybox，
  # 否则脚本里那句 `exec "$0"`（重开一个干净 shell）会变成无参 exec busybox
  # → 直接打印 usage 退出。
  #
  # 欢迎语 = ASCII 横幅 + 当前系统 + 主机名 + 快捷键提示。
  # hostname 从文件读而不调用 hostname 命令：busybox 的 hostname 在 proot 下
  # 有时拿不到（需要 /proc/sys/kernel/hostname 的写入权限），直接读文件最稳。
  BANNER=""
  [ -f "$ROOTFS_DIR/etc/agentpad-banner.txt" ] && BANNER=$(cat "$ROOTFS_DIR/etc/agentpad-banner.txt")
  HOST="agent-workspace"
  [ -s "$ROOTFS_DIR/etc/hostname" ] && HOST=$(cat "$ROOTFS_DIR/etc/hostname")

  # ⚠ 系统名必须从 **rootfs 里** 读，不能在宿主机读 /etc/os-release ——
  #   那是 Android 的 os-release（PRETTY_NAME 通常是 "Android" 或干脆没有），
  #   之前写成不带前缀的 /etc/os-release，所以永远显示 "system: Linux"。
  OSNAME="Linux"
  if [ -s "$ROOTFS_DIR/etc/os-release" ]; then
    OSNAME=$(
      . "$ROOTFS_DIR/etc/os-release" 2>/dev/null
      if [ -n "${PRETTY_NAME:-}" ]; then echo "$PRETTY_NAME"
      elif [ -n "${NAME:-}" ]; then echo "$NAME ${VERSION_ID:-}"
      fi
    )
  fi
  # os-release 缺失时按特征文件兜底判断
  # 用 `read` 而不是 `cat`：Android 的 /system/bin/sh 是 mksh，cat 有但不保证
  # 所有精简 ROM 都带；read 是内建，零依赖。
  if [ -z "$OSNAME" ] || [ "$OSNAME" = "Linux" ]; then
    if [ -s "$ROOTFS_DIR/etc/alpine-release" ]; then
      _v=""
      read -r _v < "$ROOTFS_DIR/etc/alpine-release" 2>/dev/null || true
      OSNAME="Alpine Linux ${_v}"
    elif [ -s "$ROOTFS_DIR/etc/debian_version" ]; then
      _v=""
      read -r _v < "$ROOTFS_DIR/etc/debian_version" 2>/dev/null || true
      OSNAME="Debian ${_v}"
    fi
  fi
  "$PROOT" $ARGS "$ROOTFS_SHELL" $SHELL_APP -c 'B="$1"; H="$2"; O="$3"; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export HOME=/root; export TERM=xterm-256color; [ -f /root/.agentpad_rc ] && . /root/.agentpad_rc; [ -n "'"$JRE_LIB"'" ] && export LD_LIBRARY_PATH="'"$JRE_LIB"':${LD_LIBRARY_PATH:-}"; [ -n "$B" ] && printf "%s\n\n" "$B"; printf "\033[1;36m  AgentPad Terminal\033[0m \033[2m- PRoot Linux sandbox\033[0m\n"; printf "\033[2m  host: %s · system: %s\033[0m\n" "$H" "$O"; printf "\033[2m  pinch = zoom · swipe up = history · long-press = copy\033[0m\n\n"; exec "$0"' "$SHELL_ZERO" "$BANNER" "$HOST" "$OSNAME"
else
  # 命令执行：同样带上 applet 名。busybox 下 = `busybox sh -c '<命令>' sh <命令>`，
  # 非 busybox（dash/bash）下 SHELL_APP 为空串，行为与原来完全一致。
  "$PROOT" $ARGS "$ROOTFS_SHELL" $SHELL_APP -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export HOME=/root; export TERM=xterm-256color; [ -f /root/.agentpad_rc ] && . /root/.agentpad_rc; [ -n "'"$JRE_LIB"'" ] && export LD_LIBRARY_PATH="'"$JRE_LIB"':${LD_LIBRARY_PATH:-}"; exec "$@"' sh "$@"
fi
