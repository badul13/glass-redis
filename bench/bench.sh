#!/usr/bin/env bash
# README 처리량 표 재현 - Redis 7.2.4 와 glass-redis 를 같은 조건으로
#
# - 서버·벤치마크 모두 Docker 컨테이너, 각각 --cpus 2 (호스트 영향 차단)
# - 처리량: redis-benchmark 예열 1회 버리고 3회 중앙값 (초당 요청 수)
# - BGSAVE 정지: 키 26만 개 상태에서 BGSAVE, 그동안 redis-cli --latency 최대값 3회 중앙값
#   (Redis 는 fork 순간, glass-redis 는 실행 스레드의 직렬화 시간이 여기에 잡힘)
#
# 사용: bench/bench.sh            (레포 루트 어디서든, Docker 필요)
#       RUNS=5 REQUESTS=500000 bench/bench.sh
set -euo pipefail

RUNS=${RUNS:-3}
REQUESTS=${REQUESTS:-300000}
CLIENTS=${CLIENTS:-50}
KEYS=${KEYS:-260000}
CPUS=${CPUS:-2}
REDIS_IMAGE=redis:7.2.4
GLASS_IMAGE=glass-redis:bench
NET=glass-bench
SERVER=glass-bench-server

cd "$(dirname "$0")/.."

cleanup() {
    docker rm -f "$SERVER" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 클라이언트 쪽 명령 - 서버와 같은 네트워크의 별도 컨테이너
client() {
    docker run --rm --network "$NET" --cpus "$CPUS" "$REDIS_IMAGE" "$@"
}

median() {
    sort -n | awk '{ v[NR] = $1 } END { print v[int((NR + 1) / 2)] }'
}

# 서버 띄우기 - $1: redis|glass, 나머지: 서버 옵션
start_server() {
    local kind=$1; shift
    docker rm -f "$SERVER" >/dev/null 2>&1 || true
    if [[ $kind == redis ]]; then
        docker run -d --name "$SERVER" --network "$NET" --cpus "$CPUS" "$REDIS_IMAGE" \
            redis-server --port "$PORT" --save '' "$@" >/dev/null
    else
        docker run -d --name "$SERVER" --network "$NET" --cpus "$CPUS" "$GLASS_IMAGE" \
            --port "$PORT" --no-dashboard --save '' "$@" >/dev/null
    fi
    until client redis-cli -h "$SERVER" -p "$PORT" ping >/dev/null 2>&1; do sleep 0.5; done
}

# redis-benchmark 한 항목 - $1: set|get, $2: 파이프라인 수
# 첫 1회는 버림 - JVM 은 JIT 컴파일 전 첫 회차가 절반 수준까지 떨어짐, 공정하게 두 서버 모두 적용
# stderr 버림 - glass-redis 엔 CONFIG 가 없어 "Could not fetch server CONFIG" 경고
throughput() {
    local test=$1 pipeline=$2
    for ((i = 0; i <= RUNS; i++)); do
        client redis-benchmark -h "$SERVER" -p "$PORT" -t "$test" -n "$REQUESTS" -c "$CLIENTS" \
            -P "$pipeline" -q --csv 2>/dev/null | awk -F'"' 'NR == 2 { print $4 }'
    done | tail -n +2 | median
}

# BGSAVE 동안 명령이 멈춘 최대 시간(ms)
bgsave_stall() {
    client sh -c "seq 1 $KEYS | awk '{ printf \"*3\r\n\$3\r\nSET\r\n\$%d\r\nkey:%s\r\n\$5\r\nvalue\r\n\", length(\$1) + 4, \$1 }' \
        | redis-cli -h $SERVER -p $PORT --pipe" >/dev/null
    echo "  키 $(client redis-cli -h "$SERVER" -p "$PORT" dbsize)개 채움" >&2
    for ((i = 0; i < RUNS; i++)); do
        # --latency 는 터미널이 아니면 약 1초 재고 "최소 최대 평균 횟수" 한 줄 남기고 끝남 → 그 창 안에서 BGSAVE
        client sh -c "
            redis-cli -h $SERVER -p $PORT --latency --raw > /tmp/lat &
            sleep 0.3
            redis-cli -h $SERVER -p $PORT bgsave > /dev/null
            wait
            awk '{ print \$2 }' /tmp/lat"
        # 다음 회차 전에 저장 끝나기 기다림
        sleep 3
    done | median
}

measure() {
    local kind=$1
    if [[ $kind == redis ]]; then PORT=6379; else PORT=6380; fi
    echo "== $kind" >&2

    start_server "$kind"
    SET1=$(throughput set 1);   echo "  SET        $SET1" >&2
    GET1=$(throughput get 1);   echo "  GET        $GET1" >&2
    SET16=$(throughput set 16); echo "  SET P16    $SET16" >&2
    GET16=$(throughput get 16); echo "  GET P16    $GET16" >&2

    start_server "$kind" --appendonly yes --appendfsync everysec
    AOF16=$(throughput set 16); echo "  SET P16 AOF $AOF16" >&2

    start_server "$kind"
    STALL=$(bgsave_stall);      echo "  BGSAVE 정지 ${STALL}ms" >&2

    printf '%s %s %s %s %s %s\n' "$SET1" "$GET1" "$SET16" "$GET16" "$AOF16" "$STALL"
}

docker build -q -t "$GLASS_IMAGE" . >/dev/null
docker pull -q "$REDIS_IMAGE" >/dev/null
docker network create "$NET" >/dev/null

read -r r1 r2 r3 r4 r5 r6 < <(measure redis)
read -r g1 g2 g3 g4 g5 g6 < <(measure glass)

# 초당 요청 수 → "13.1만" 형식
man() { awk -v n="$1" 'BEGIN { printf "%.1f만", n / 10000 }'; }

cat <<EOF

| | Redis 7.2.4 | glass-redis |
|---|---:|---:|
| \`SET\` | $(man "$r1") | $(man "$g1") |
| \`GET\` | $(man "$r2") | $(man "$g2") |
| \`SET\` 파이프라인 16 | $(man "$r3") | $(man "$g3") |
| \`GET\` 파이프라인 16 | $(man "$r4") | $(man "$g4") |
| \`SET\` 파이프라인 16, AOF everysec | $(man "$r5") | $(man "$g5") |
| \`BGSAVE\` 동안 명령이 멈추는 시간 (키 $((KEYS / 10000))만 개) | ${r6}ms | ${g6}ms |
EOF
