#!/usr/bin/env bash
#
# persona 探针的一键入口。
#
# 它存在的唯一理由是**拼 classpath 太啰嗦** —— 而那段啰嗦每个想跑的人都得抄一遍，
# 抄错一个字符就得到一句 "ClassNotFoundException"，然后开始怀疑是不是代码坏了。
# 所以把它钉在这里，跑的人只需要 export 一个 key。
#
# 三件事：生成 cp.txt（缺了才生成）→ 生成 argfile → 编译 + 跑。
#
# ⚠️ 为什么用 @argfile 而不是把 classpath 直接写在命令行上
#    cp.txt 里是 `<本机 maven 仓库>/…jar;<下一个绝对路径>`：反斜杠 + Windows 的 `;` 分隔符。
#    直接放命令行要穿过 bash 和 MSYS 两层转义，而这两层对 `;` 和 `\` 的处理都各有主张。
#    放进文件则原样交给 java，谁也不碰它。
#    代价：argfile 里反斜杠有转义含义，所以生成时统一换成 `/`（Windows 的 java 两种都认）。
#
# ⚠️ 为什么判定不在这里用 node 做
#    这台机器上 Node 起不了子进程（spawnSync 对**任何**命令都 EBUSY）。
#    详见 PersonaProbe.java 类注释里"为什么这个探针是 Java 的"。
set -euo pipefail
cd "$(dirname "$0")/.."

BACKEND="backend"
PROBE="_probe"

if [ ! -f "$PROBE/cp.txt" ]; then
  echo "→ 生成 $PROBE/cp.txt（离线）"
  (cd "$BACKEND" && mvn -o -q dependency:build-classpath -Dmdep.outputFile="../$PROBE/cp.txt")
fi

CP="$PROBE/classes;$BACKEND/target/classes;$(sed 's|\\|/|g' "$PROBE/cp.txt")"
printf -- '-cp "%s"\n' "$CP" > "$PROBE/.probe-args"

mkdir -p "$PROBE/classes" "$PROBE/out"
javac @"$PROBE/.probe-args" -encoding UTF-8 -nowarn -d "$PROBE/classes" "$PROBE/PersonaProbe.java"
exec java @"$PROBE/.probe-args" com.hellen.kidtrip.plan.PersonaProbe
