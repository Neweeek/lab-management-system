#!/usr/bin/env sh
#
# SQLite 在线备份。
#
# 修复前的问题：
#   - 新开的 sqlite3 连接没有 busy_timeout（应用连接有，CLI 默认是 0），
#     遇到写锁会立刻 SQLITE_BUSY 并因 set -e 中止整次备份；
#   - 路径全部相对调用者的 cwd，从 cron 调用会写到意外位置或直接失败；
#   - .sha256 记录的是**容器内**路径，宿主机 `sha256sum -c` 无法使用；
#   - 备份文件由容器内 root 创建、权限 644，所有本机用户可读（内含密码哈希与周报内容）；
#   - 没有任何保留策略，每次还把库存两份，磁盘会无限增长。
#
# 用法：./deploy/backup.sh [目标目录]     默认 ./backups
# 可用环境变量：BACKUP_KEEP（保留份数，默认 14）

set -eu

# 固定以仓库根目录为基准，避免 cwd 不同导致路径漂移。
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir/.."

destination=${1:-./backups}
keep=${BACKUP_KEEP:-14}
stamp=$(date +%Y%m%d-%H%M%S)

# 备份包含口令哈希与成员信息，默认只允许属主读写。
umask 077
mkdir -p "$destination"
# 容器内可见的同一目录（compose 把 ./data 挂到 /app/data）。
mkdir -p ./data/backups

command -v docker >/dev/null 2>&1 || { echo "错误：未找到 docker" >&2; exit 1; }
docker compose ps --status running --services 2>/dev/null | grep -qx app \
  || { echo "错误：app 服务未在运行，无法执行在线备份" >&2; exit 1; }

container_backup="/app/data/backups/lab-${stamp}.db"
host_backup="./data/backups/lab-${stamp}.db"
if [ "$destination" = "./data/backups" ]; then
  # 目标就是容器可见目录时，不必再拷一次。
  final_backup="$host_backup"
else
  final_backup="${destination}/lab-${stamp}.db"
fi

echo "正在备份到 ${final_backup} ..."
# .timeout 让 CLI 连接遇到写锁时等待而不是立刻失败。
# .backup 是 SQLite 在线备份 API：不需要停服，且能正确包含 WAL 中已提交的数据。
docker compose exec -T app sqlite3 -cmd ".timeout 10000" /app/data/lab.db ".backup '${container_backup}'"

# 校验备份本身可读且结构完整，避免留下一个损坏的"备份"。
integrity=$(docker compose exec -T app sqlite3 "$container_backup" "PRAGMA integrity_check;")
if [ "$integrity" != "ok" ]; then
  echo "错误：备份完整性检查未通过：$integrity" >&2
  echo "已保留文件供排查：$host_backup" >&2
  exit 1
fi

if [ "$final_backup" != "$host_backup" ]; then
  cp "$host_backup" "$final_backup"
  rm -f "$host_backup"
fi
chmod 600 "$final_backup"

# 在宿主机上计算校验和，文件名与备份同目录，`sha256sum -c` 可直接使用。
( cd "$(dirname -- "$final_backup")" && sha256sum "$(basename -- "$final_backup")" > "$(basename -- "$final_backup").sha256" )
chmod 600 "${final_backup}.sha256"

# 保留最近 $keep 份，其余删除。
pruned=0
if [ "$keep" -gt 0 ] 2>/dev/null; then
  for old in $(ls -1t "${destination}"/lab-*.db 2>/dev/null | tail -n "+$((keep + 1))"); do
    rm -f "$old" "${old}.sha256"
    pruned=$((pruned + 1))
  done
fi

size=$(wc -c < "$final_backup" | tr -d ' ')
echo "备份完成：${final_backup}（${size} 字节，完整性 ok，清理旧备份 ${pruned} 份）"
echo "请额外把 ${final_backup} 同步到对象存储或另一台机器。"
