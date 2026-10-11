#!/bin/bash
# クラウドのセッション (claude.ai/code) で, Docker を使うための補助.
#
#   scripts/cloud-docker.sh start        Docker のデーモン (dockerd) を起動する (起動済みなら, 何もしない)
#   scripts/cloud-docker.sh hook         SessionStart フック用. クラウドのときだけ, start する
#   scripts/cloud-docker.sh build        開発用のイメージ (thermon-dev) を, プロキシと CA の設定を足して作る
#   scripts/cloud-docker.sh run <引数>   docker compose run --rm <引数> を, プロキシの設定を足して実行する
#                                        (例: scripts/cloud-docker.sh run build)
#
# なぜ必要か: クラウドの VM の外への通信は, 全て, プロキシ (HTTPS_PROXY=http://127.0.0.1:<ポート>) と,
# そのプロキシの CA 証明書 (SSL_CERT_FILE) を使う. コンテナは, どちらも引き継がないので, 通常の
# docker compose build は, curl の終了コード 60 (証明書の検証の失敗) で失敗する.
# プロキシのポートは, セッションの途中でも変わるので, 実行のたびに, $HTTPS_PROXY から読む.
#
# リポジトリの Dockerfile は, 変えない (一時的なコピーに, CA を取り込む行を足して, ビルドする).
# ローカルや CI では, 使わない (プロキシの設定が, ないとき: ビルドは, 通常どおり).
#
# 環境変数 (省略可): THERMON_IMAGE (イメージの名前. 既定は thermon-dev:latest),
# CLOUD_CA_BUNDLE (プロキシの CA の束. 既定は SSL_CERT_FILE, なければ /root/.ccr/ca-bundle.crt),
# CLOUD_DOCKER_DRYRUN (空でなければ, build は, 実行するコマンドと Dockerfile の差分を, 表示するだけ)

set -u

IMAGE="${THERMON_IMAGE:-thermon-dev:latest}"
CA_BUNDLE="${CLOUD_CA_BUNDLE:-${SSL_CERT_FILE:-/root/.ccr/ca-bundle.crt}}"
DOCKERD_LOG=/tmp/dockerd.log
START_WAIT_S=30
WORK=""
# 一時ディレクトリは, 終了のときに, 必ず消す
trap '[ -z "$WORK" ] || rm -rf "$WORK"' EXIT

repo_root() {
    cd "$(dirname "$0")/.." && pwd
}

# $HTTPS_PROXY (http://host:port) から, ホストとポートを取り出す. なければ, 空にする
PROXY_HOST=""
PROXY_PORT=""
parse_proxy() {
    local hp="${HTTPS_PROXY:-${https_proxy:-}}"
    [ -n "$hp" ] || return 0
    hp="${hp#*://}"
    hp="${hp%%/*}"
    hp="${hp##*@}"
    PROXY_HOST="${hp%:*}"
    PROXY_PORT="${hp##*:}"
}

# Java (sdkmanager, Gradle) に渡す, プロキシの設定
java_proxy_options() {
    [ -n "$PROXY_HOST" ] || return 0
    echo "-Dhttp.proxyHost=$PROXY_HOST -Dhttp.proxyPort=$PROXY_PORT" \
        "-Dhttps.proxyHost=$PROXY_HOST -Dhttps.proxyPort=$PROXY_PORT" \
        "-Dhttp.nonProxyHosts=localhost|127.0.0.1"
}

docker_ready() {
    docker info > /dev/null 2>&1
}

cmd_start() {
    if docker_ready; then
        echo "dockerd is running"
        return 0
    fi
    if ! command -v dockerd > /dev/null 2>&1; then
        echo "dockerd was not found" >&2
        return 1
    fi
    # フックの終了で止められないよう, 新しいセッションで, 切り離して起動する
    setsid nohup dockerd > "$DOCKERD_LOG" 2>&1 &
    local i
    for ((i = 0; i < START_WAIT_S; i++)); do
        if docker_ready; then
            echo "dockerd started"
            return 0
        fi
        sleep 1
    done
    echo "dockerd did not start within ${START_WAIT_S} s. See $DOCKERD_LOG" >&2
    return 1
}

# SessionStart フック: クラウドのときだけ起動する. 失敗しても, セッションの開始は, 止めない
cmd_hook() {
    if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
        return 0
    fi
    cmd_start || true
    return 0
}

# CA の束を, 証明書ごとに 1 ファイルに分ける (1 ファイルのまま入れると, Java の信頼ストアには,
# 最初の 1 枚しか入らず, Gradle が PKIX のエラーで落ちる)
split_ca() {
    local bundle="$1" dir="$2"
    mkdir -p "$dir"
    awk -v dir="$dir" '
        /-----BEGIN CERTIFICATE-----/ { n++; f = sprintf("%s/ca-%03d.crt", dir, n) }
        f != "" { print > f }
        /-----END CERTIFICATE-----/ { f = "" }
    ' "$bundle"
}

# Dockerfile のコピーを作る. 最初の apt の段落 (&& rm -rf /var/lib/apt/lists/*) の直後に,
# CA を取り込む行と, Java のプロキシの引数 (ARG なので, イメージには, 残らない) を足す
make_dockerfile() {
    local src="$1" dst="$2"
    awk '
        { print }
        !done && $0 ~ /&& rm -rf \/var\/lib\/apt\/lists\/\*/ {
            print ""
            print "# クラウド用: プロキシの CA を, 信頼ストア (Java も) に入れる"
            print "COPY ca/ /usr/local/share/ca-certificates/cloud/"
            print "RUN update-ca-certificates"
            print "ARG JAVA_TOOL_OPTIONS"
            done = 1
        }
        END { if (!done) exit 1 }
    ' "$src" > "$dst"
}

cmd_build() {
    local root work java_opts
    root="$(repo_root)" || return 1
    if [ ! -f "$CA_BUNDLE" ]; then
        echo "The CA bundle was not found: $CA_BUNDLE" >&2
        return 1
    fi
    cmd_start || return 1
    parse_proxy
    java_opts="$(java_proxy_options)"

    work="$(mktemp -d)"
    WORK="$work"
    split_ca "$CA_BUNDLE" "$work/ca"
    if ! ls "$work"/ca/*.crt > /dev/null 2>&1; then
        echo "No certificate was found in $CA_BUNDLE" >&2
        return 1
    fi
    if ! make_dockerfile "$root/Dockerfile" "$work/Dockerfile"; then
        echo "The place to add the CA was not found in the Dockerfile" >&2
        return 1
    fi

    local args=(--network host --target dev -t "$IMAGE" -f "$work/Dockerfile")
    if [ -n "$PROXY_HOST" ]; then
        args+=(
            --build-arg "HTTP_PROXY=${HTTPS_PROXY:-$https_proxy}"
            --build-arg "HTTPS_PROXY=${HTTPS_PROXY:-$https_proxy}"
            --build-arg "http_proxy=${HTTPS_PROXY:-$https_proxy}"
            --build-arg "https_proxy=${HTTPS_PROXY:-$https_proxy}"
            --build-arg "NO_PROXY=localhost,127.0.0.1"
            --build-arg "JAVA_TOOL_OPTIONS=$java_opts"
        )
    fi
    echo "Building $IMAGE (proxy: ${PROXY_HOST:-none}:${PROXY_PORT:-none}, CA files: $(ls "$work"/ca | wc -l))"
    # 確認用: ビルドせずに, 実行するコマンドと, 作った Dockerfile の差分を表示する
    if [ -n "${CLOUD_DOCKER_DRYRUN:-}" ]; then
        printf '%q ' docker build "${args[@]}" "$work"
        echo
        diff "$root/Dockerfile" "$work/Dockerfile"
        return 0
    fi
    docker build "${args[@]}" "$work"
}

cmd_run() {
    local root work java_opts svc
    root="$(repo_root)" || return 1
    cmd_start || return 1
    parse_proxy
    java_opts="$(java_proxy_options)"

    work="$(mktemp -d)"
    WORK="$work"
    {
        echo "services:"
        for svc in $(cd "$root" && docker compose config --services | grep -v '^emulator$'); do
            echo "  $svc:"
            echo "    network_mode: host"
            if [ -n "$PROXY_HOST" ]; then
                echo "    environment:"
                echo "      - HTTPS_PROXY=${HTTPS_PROXY:-$https_proxy}"
                echo "      - HTTP_PROXY=${HTTPS_PROXY:-$https_proxy}"
                echo "      - https_proxy=${HTTPS_PROXY:-$https_proxy}"
                echo "      - http_proxy=${HTTPS_PROXY:-$https_proxy}"
                echo "      - NO_PROXY=localhost,127.0.0.1"
                echo "      - JAVA_TOOL_OPTIONS=$java_opts"
            fi
        done
    } > "$work/override.yml"
    (cd "$root" && docker compose -f docker-compose.yml -f "$work/override.yml" run --rm "$@")
}

case "${1:-}" in
    start) cmd_start ;;
    hook) cmd_hook ;;
    build) cmd_build ;;
    run)
        shift
        cmd_run "$@"
        ;;
    *)
        echo "Usage: $0 start|hook|build|run <docker compose run の引数>" >&2
        exit 2
        ;;
esac
