#!/usr/bin/env sh
#
# 从备份恢复实时数据库。
#
# 修复前的问题（这是一个真正危险的脚本）：
#   - 只打印一句提示 + sleep 5，随后无条件销毁实时库；cron/CI 等非交互调用方
#     会在 5 秒后静默覆盖生产数据；
#   - 不校验 .sha256，也不做 PRAGMA integrity_check，损坏或拿错的文件会被直接启用；
#   - 不先备份当前库，操作完全不可逆；
#   - set -eu 且没有 trap：若 cp 因权限失败（容器曾以 root 创建 lab.db），
#     脚本会在**停服之后**中止，把服务留在宕机状态。
#
# 用法：
#   ./deploy/restore.sh backups/lab-20260917-101530.db          # 交互确认
#   ./deploy/restore.sh --yes backups/lab-....db                # 跳过确认（自动化）
#   ./deploy/restore.sh --dry-run backups/lab-....db            # 只校验，不改动

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir/.."

assume_yes=0
dry_run=0
backup_file=''
for arg in "$@"; do
  case "$arg" in
    --yes|-y) assume_yes=1 ;;
    --dry-run) dry_run=1 ;;
    -*) echo "未知参数：$arg" >&2; exit 2 ;;
    *) backup_file=$arg ;;
  esac
done
if [ -z "$backup_file" ]; then
  echo "用法：$0 [--yes] [--dry-run] path/to/lab-YYYYMMDD-HHMMSS.db" >&2
  exit 2
fi
[ -f "$backup_file" ] || { echo "错误：备份文件不存在：$backup_file" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "错误：未找到 docker" >&2; exit 1; }

# --- 1. 校验校验和（如果备份目录里有同名 .sha256） ---------------------------
if [ -f "${backup_file}.sha256" ]; then
  echo "正在校验 SHA-256 ..."
  ( cd "$(dirname -- "$backup_file")" && sha256sum -c "$(basename -- "$backup_file").sha256" ) \
    || { echo "错误：校验和不匹配，文件可能损坏，已中止。" >&2; exit 1; }
else
  echo "提示：未找到 ${backup_file}.sha256，跳过校验和检查。"
fi

# --- 2. 用一次性容器做 SQLite 完整性检查 ------------------------------------
echo "正在检查 SQLite 完整性 ..."
backup_abs=$(CDPATH= cd -- "$(dirname -- "$backup_file")" && pwd)/$(basename -- "$backup_file")
integrity=$(docker run --rm -v "$backup_abs:/restore/lab.db:ro" --entrypoint sqlite3 \
  "$(docker compose images -q app 2>/dev/null | head -n1 || true)" /restore/lab.db "PRAGMA integrity_check;" 2>/dev/null \
  || echo "")
if [ -z "$integrity" ]; then
  # 拿不到应用镜像时退化为本地 sqlite3（宿主机可能没装）。
  if command -v sqlite3 >/dev/null 2>&1; then
    integrity=$(sqlite3 "$backup_abs" "PRAGMA integrity_check;")
  else
    echo "提示：无法进行完整性检查（应用镜像未构建且宿主机没有 sqlite3），请自行确认备份可用。" >&2
    integrity=unknown
  fi
fi
if [ "$integrity" != "ok" ] && [ "$integrity" != "unknown" ]; then
  echo "错误：备份文件完整性检查未通过：$integrity" >&2
  exit 1
fi
echo "完整性检查：$integrity"

if [ "$dry_run" -eq 1 ]; then
  echo "--dry-run：校验通过，未做任何改动。"
  exit 0
fi

# --- 3. 二次确认 -------------------------------------------------------------
if [ "$assume_yes" -ne 1 ]; then
  printf '即将用 %s 覆盖实时数据库。当前数据库会先另存一份快照。\n输入 yes 继续：' "$backup_file"
  read -r answer
  [ "$answer" = "yes" ] || { echo "已取消，未做任何改动。"; exit 0; }
fi

# --- 4. 恢复前快照（保证可回滚） --------------------------------------------
mkdir -p ./data
if [ -f ./data/lab.db ]; then
  safety="./data/lab.db-before-restore-$(date +%Y%m%d-%H%M%S)"
  echo "正在保存恢复前快照：$safety"
  docker compose stop app >/dev/null 2>&1 || true
  cp -p ./data/lab.db "$safety" 2>/dev/null || cp ./data/lab.db "$safety"
  chmod 600 "$safety"
fi

# --- 5. 停服 → 替换 → 启动，并保证失败时把服务重新拉起来 --------------------
restore_ok=0
recover() {
  if [ "$restore_ok" -ne 1 ]; then
    echo "恢复过程中出错，正在尝试把 app 重新启动，避免服务停在宕机状态。" >&2
    docker compose start app >/dev/null 2>&1 || true
  fi
}
trap recover EXIT INT TERM

echo "正在停止 app ..."
docker compose stop app
# stop 之后再清理 WAL/SHM，否则会丢掉已提交但未 checkpoint 的事务。
cp "$backup_file" ./data/lab.db
rm -f ./data/lab.db-wal ./data/lab.db-shm

echo "正在启动 app ..."
docker compose start app
restore_ok=1

# --- 6. 等健康检查通过再报成功 ----------------------------------------------
printf '等待服务就绪'
ready=0
for _ in $(seq 1 30); do
  if docker compose exec -T app wget -q -O /dev/null http://127.0.0.1:8080/api/health 2>/dev/null; then
    ready=1
    break
  fi
  printf '.'
  sleep 2
done
printf '\n'
if [ "$ready" -eq 1 ]; then
  echo "恢复完成：服务已就绪。请登录核对数据后再对外开放。"
else
  echo "警告：恢复已写入，但服务在 60 秒内未通过健康检查，请查看 docker compose logs -f app。" >&2
  exit 1
fi
