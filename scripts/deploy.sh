#!/usr/bin/env bash
set -euo pipefail
umask 027

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export DEPLOY_ROOT=${DEPLOY_ROOT:-/opt/rsdp-office}
export ENV_FILE=${ENV_FILE:-$DEPLOY_ROOT/.env}
export IMAGE_TAG=${IMAGE_TAG:-$(git -C "$repo_root" rev-parse --short=12 HEAD)}

compose() {
    docker compose --project-name rsdp-office --env-file "$ENV_FILE" -f "$1" "${@:2}"
}

check() {
    for tool in docker curl git flock python3 gzip; do
        command -v "$tool" >/dev/null || { echo "缺少命令：$tool" >&2; exit 1; }
    done
    docker info >/dev/null
    docker compose version
    test -r "$ENV_FILE" || { echo "无法读取服务器配置：$ENV_FILE" >&2; exit 1; }
    python3 - "$ENV_FILE" "$repo_root/compose.deploy.yaml" <<'PY'
import stat, subprocess, sys
from pathlib import Path
p = Path(sys.argv[1])
if stat.S_IMODE(p.stat().st_mode) & 0o007:
    sys.exit('.env 不应允许其他用户访问；请设置为 0640 或 0600。')
values = {}
for line in p.read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        values[key.strip()] = value.strip().strip('\"\'')
for key in ('POSTGRES_PASSWORD', 'DASHSCOPE_API_KEY', 'RSDP_AUTH_SECRET'):
    if not values.get(key):
        sys.exit('服务器 .env 缺少 ' + key)
if len(values['RSDP_AUTH_SECRET']) < 32:
    sys.exit('RSDP_AUTH_SECRET 至少需要 32 个字符。')
if not values.get('RSDP_BOOTSTRAP_PASSWORD'):
    # PostgreSQL owns its data directory; Jenkins need not read it on the host.
    result = subprocess.run([
        'docker', 'compose', '--project-name', 'rsdp-office', '--env-file', str(p),
        '-f', sys.argv[2], 'exec', '-T', 'postgres', 'sh', '-c',
        '''psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM office_user WHERE status='active'"'''
    ], capture_output=True, text=True)
    if result.returncode or not result.stdout.strip().isdigit() or int(result.stdout.strip()) == 0:
        sys.exit('未能核验已有有效用户；首次部署必须配置 RSDP_BOOTSTRAP_PASSWORD。')
print('服务器 .env 必填项及权限检查通过（不输出配置值）。')
PY
    compose "$repo_root/compose.deploy.yaml" config --quiet
    for dir in release backups; do
        test -w "$DEPLOY_ROOT/$dir" || { echo "无写入权限：$DEPLOY_ROOT/$dir" >&2; exit 1; }
    done
}

case "${1:-check}" in
    check)
        check
        ;;
    build)
        check
        compose "$repo_root/compose.deploy.yaml" pull --policy missing postgres
        compose "$repo_root/compose.deploy.yaml" build
        ;;
    deploy)
        check
        for image in "rsdp-office-backend:$IMAGE_TAG" "rsdp-office-cad-parser:$IMAGE_TAG" "rsdp-office-frontend:$IMAGE_TAG" postgres:16-alpine; do
            docker image inspect "$image" >/dev/null
        done
        exec 9>"$DEPLOY_ROOT/release/deploy.lock"
        flock -n 9 || { echo '已有部署进行中。' >&2; exit 1; }
        release="$DEPLOY_ROOT/release/$IMAGE_TAG"
        mkdir -p "$release/backend/cad-parser" "$release/frontend" "$release/db/migrations"
        # Store only non-secret deployment files, outside Jenkins' workspace.
        cp "$repo_root/compose.deploy.yaml" "$release/compose.deploy.yaml"
        cp "$repo_root/backend/src/main/resources/db/schema.sql" "$release/db/schema.sql"
        cp "$repo_root/backend/src/main/resources/db/migrations/"V*.sql "$release/db/migrations/"
        stamp=$(date -u +%Y%m%dT%H%M%SZ)
        running=$(compose "$release/compose.deploy.yaml" ps --status running --services)
        if [[ "$running" == *office-backend* ]]; then
            compose "$release/compose.deploy.yaml" stop frontend office-backend
        fi
        # No down/-v, pruning, or automatic rollback across schema migrations.
        compose "$release/compose.deploy.yaml" up -d --no-build --pull never --wait --wait-timeout 180 postgres cad-parser
        # Also back up an existing database whose container was stopped before
        # deployment. A first installation simply backs up its empty database.
        backup="$DEPLOY_ROOT/backups/$stamp-$IMAGE_TAG.sql.gz"
        compose "$release/compose.deploy.yaml" exec -T postgres sh -c \
            'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB"' | gzip > "$backup"
        gzip -t "$backup"
        test -s "$backup"
        echo "数据库备份：$backup"
        # The application's startup handles its other migrations. These two
        # spatial migrations require explicit application, including on a new DB.
        cat "$release/db/schema.sql" "$release/db/migrations/"V*.sql | \
            compose "$release/compose.deploy.yaml" exec -T postgres sh -c \
                'psql -v ON_ERROR_STOP=1 --single-transaction -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$release/migrations.log"
        compose "$release/compose.deploy.yaml" up -d --no-build --pull never --wait --wait-timeout 300
        port=$(compose "$release/compose.deploy.yaml" port frontend 80)
        curl --fail --silent "http://$port/healthz" >/dev/null
        code=$(curl --silent --output /dev/null --write-out '%{http_code}' "http://$port/api/auth/me")
        test "$code" = 401 || { echo "匿名认证检查失败：HTTP $code" >&2; exit 1; }
        printf '%s\n' "$IMAGE_TAG" > "$DEPLOY_ROOT/release/current-image-tag"
        compose "$release/compose.deploy.yaml" ps
        echo "已部署 $IMAGE_TAG；本机入口 http://$port，公网需通过 HTTPS 反向代理。"
        ;;
    *)
        echo 'Usage: bash scripts/deploy.sh {check|build|deploy}' >&2
        exit 2
        ;;
esac
