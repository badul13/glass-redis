import { useState } from 'react'
import type { Score, SortedSetSnapshot } from '../types'
import type { SortedSetState } from '../useSortedSet'

/** 칸 하나의 폭과 층 하나의 높이. */
const COLUMN = 76
const ROW = 26
const BOX_WIDTH = 30
const BOX_HEIGHT = 18
const PAD_X = 16
const PAD_TOP = 14
/** 맨 아래 멤버 이름과 점수를 쓰는 자리. */
const LABEL_HEIGHT = 38

interface Step {
  from: number
  level: number
  span: number
}

/**
 * 고른 Sorted Set 이 지금 어떤 모양으로 담겨 있는지 그린다. 작을 때는 listpack({@link ListpackStrip}),
 * 커지면 스킵 리스트다. 아래는 스킵 리스트 그림 이야기다.
 *
 * <p>가로로 늘어선 칸은 순서대로의 노드다. 0번 칸은 원소 없는 머리 노드, k 번 칸이 k 번째 노드다.
 * 노드마다 올라간 층수만큼 상자가 쌓이고, 같은 층의 다음 상자로 화살표가 간다.
 * 화살표 위 숫자가 span — 1층 기준으로 몇 칸을 건너뛰는지다.
 *
 * <p>노드에 커서를 올리면 그 노드의 순위를 셀 때 서버가 걷는 길을 칠한다.
 * 맨 위층에서 출발해 넘치기 직전까지 건너고, 한 층 내려가기를 반복한다. 지나온 span 의 합이 순위다.
 */
export function SortedSetView({ state, onClose }: { state: SortedSetState | null; onClose: () => void }) {
  const [hovered, setHovered] = useState<number | null>(null)
  const snapshot = state?.snapshot

  return (
    <section className="panel zset-panel">
      <header className="panel-head">
        <h2>
          Sorted Set 내부 구조{snapshot && <span className="zset-key"> · {snapshot.key}</span>}
        </h2>
        <span className="hint">{snapshot ? summary(snapshot) : '불러오는 중'}</span>
        <button type="button" className="close" onClick={onClose}>
          닫기
        </button>
      </header>

      {snapshot?.status === 'missing' && <p className="empty">키 없음 · 삭제 또는 만료</p>}
      {snapshot?.status === 'wrongType' && <p className="empty">Sorted Set 아님</p>}
      {state && state.snapshot.encoding === 'listpack' && (
        <ListpackStrip snapshot={state.snapshot} changed={state.changed} />
      )}
      {state && state.snapshot.encoding === 'skiplist' && (
        <>
          <PathCaption snapshot={state.snapshot} target={hovered} />
          <div className="skiplist-canvas">
            <Diagram snapshot={state.snapshot} changed={state.changed} hovered={hovered} onHover={setHovered} />
          </div>
        </>
      )}
    </section>
  )
}

/** 실제 Redis 기본 설정: 멤버 128개, 멤버 64바이트까지 listpack. */
const MAX_LISTPACK_ENTRIES = 128
const MAX_LISTPACK_VALUE = 64

function summary(snapshot: SortedSetSnapshot): string {
  if (snapshot.status !== 'ok') {
    return ''
  }
  const shown = snapshot.nodes.length
  const truncated = shown < snapshot.length ? ' · 앞 ' + shown + '개만 표시' : ''
  if (snapshot.encoding === 'listpack') {
    return 'listpack · 멤버 ' + snapshot.length + '개 · ' + snapshot.bytes + '바이트' + truncated
  }
  return 'skiplist · 멤버 ' + snapshot.length + '개 · ' + snapshot.level + '층' + truncated
}

/**
 * listpack 인코딩. 층도 화살표도 없이 [멤버, 점수, 멤버, 점수, ...] 가 점수순으로 바이트 배열 한 줄에 이어져 있다.
 *
 * <p>멤버가 128개 이하이고 모두 64바이트 이하인 동안은 실제 Redis 도 이렇게 담는다. 순위를 세든 점수를 찾든
 * 처음부터 훑지만, 원소가 적을 때는 그게 스킵 리스트보다 빠르고 메모리도 훨씬 적게 든다.
 * 129번째 멤버가 들어오거나 64바이트를 넘는 멤버가 들어오는 순간 skiplist 로 바뀐다(되돌아가지 않는다).
 */
function ListpackStrip({ snapshot, changed }: { snapshot: SortedSetSnapshot; changed: Set<string> }) {
  const shown = snapshot.nodes.length
  return (
    <>
      <p className="skiplist-caption">
        [멤버, 점수] 쌍을 점수순으로 이어 붙인 바이트 배열 한 줄 · 순위·점수 찾기 모두 처음부터 훑기
        <span className="dim">
          {' '}· 멤버 {MAX_LISTPACK_ENTRIES + 1}개째 또는 {MAX_LISTPACK_VALUE}바이트 초과 멤버부터 skiplist
          ({snapshot.length}/{MAX_LISTPACK_ENTRIES})
        </span>
      </p>
      <div className="skiplist-canvas">
        <div className="listpack-strip">
          <span className="lp-cell lp-header" title="총 바이트 수 4B + 원소 수 2B">
            헤더 6B
          </span>
          {snapshot.nodes.map((node) => (
            <span
              key={node.member + '@' + String(node.score)}
              className={changed.has(node.member) ? 'lp-pair fresh' : 'lp-pair'}
            >
              <span className="lp-cell lp-member" title={node.member}>
                {shorten(node.member)}
              </span>
              <span className="lp-cell lp-score">{formatScore(node.score)}</span>
            </span>
          ))}
          {shown < snapshot.length && <span className="lp-cell lp-more">…</span>}
          <span className="lp-cell lp-end" title="끝 표시 0xFF">
            FF
          </span>
        </div>
      </div>
    </>
  )
}
/** 커서를 올린 노드의 순위를 어떻게 셌는지 한 줄로. */
function PathCaption({ snapshot, target }: { snapshot: SortedSetSnapshot; target: number | null }) {
  if (target === null) {
    return <p className="skiplist-caption">노드 위에 커서 · ZRANK 가 걷는 길 표시</p>
  }
  const steps = pathTo(snapshot, target)
  return (
    <p className="skiplist-caption">
      <b>{snapshot.nodes[target - 1].member}</b> · span {steps.map((step) => step.span).join(' + ')} = {target}번째
      → ZRANK {target - 1}
      <span className="dim">
        {' '}· {steps.length}걸음 (1층만 따라가면 {target}걸음)
      </span>
    </p>
  )
}

function Diagram({
  snapshot,
  changed,
  hovered,
  onHover,
}: {
  snapshot: SortedSetSnapshot
  changed: Set<string>
  hovered: number | null
  onHover: (column: number | null) => void
}) {
  const { level, header, nodes } = snapshot
  const shown = nodes.length
  const truncated = shown < snapshot.length
  // 잘렸으면 그 너머를 가리키는 화살표가 떨어질 "…" 칸을 하나 둔다. 맨 끝 칸은 nil 이다.
  const moreColumn = shown + 1
  const nilColumn = shown + (truncated ? 2 : 1)

  const path = hovered === null ? [] : pathTo(snapshot, hovered)
  const walked = new Set(path.map((step) => step.from + ':' + step.level))

  const x = (column: number) => PAD_X + column * COLUMN
  const y = (floor: number) => PAD_TOP + (level - 1 - floor) * ROW
  const width = x(nilColumn) + 40
  const height = PAD_TOP + level * ROW + LABEL_HEIGHT

  /** 한 칸의 층별 화살표. span 을 따라가 도착 칸을 정하고, 화면 밖이면 "…" 칸으로 보낸다. */
  const arrows = (column: number, spans: number[]) =>
    spans.map((span, floor) => {
      const target = span === -1 ? nilColumn : Math.min(column + span, truncated ? moreColumn : shown)
      const lineY = y(floor) + BOX_HEIGHT / 2
      const x1 = x(column) + BOX_WIDTH
      const x2 = x(target) - 3
      const onPath = walked.has(column + ':' + floor)
      return (
        <g key={'a' + column + ':' + floor} className={span === -1 ? 'arrow nil' : onPath ? 'arrow walked' : 'arrow'}>
          <line x1={x1} y1={lineY} x2={x2} y2={lineY} markerEnd="url(#head)" />
          {span !== -1 && (
            <text x={(x1 + x2) / 2} y={lineY - 4} className="span">
              {span}
            </text>
          )}
        </g>
      )
    })

  /** 한 칸에 쌓인 상자들. */
  const tower = (column: number, floors: number, label: string) =>
    Array.from({ length: floors }, (_, floor) => (
      <g key={'b' + column + ':' + floor}>
        <rect
          x={x(column)}
          y={y(floor)}
          width={BOX_WIDTH}
          height={BOX_HEIGHT}
          rx={3}
          className={walked.has(column + ':' + floor) || column === hovered ? 'box walked' : 'box'}
        />
        {floor === 0 && (
          <text x={x(column) + BOX_WIDTH / 2} y={y(floor) + 13} className="box-label">
            {label}
          </text>
        )}
      </g>
    ))

  return (
    <svg width={width} height={height} className="skiplist-svg" onMouseLeave={() => onHover(null)}>
      <defs>
        <marker id="head" viewBox="0 0 6 6" refX="6" refY="3" markerWidth="6" markerHeight="6" orient="auto">
          <path d="M0,0 L6,3 L0,6 z" className="arrow-head" />
        </marker>
      </defs>

      {/* 층 번호 */}
      {Array.from({ length: level }, (_, floor) => (
        <text key={'f' + floor} x={2} y={y(floor) + 13} className="floor">
          {floor + 1}
        </text>
      ))}

      {tower(0, level, 'H')}
      {arrows(0, header)}

      {nodes.map((node, index) => {
        const column = index + 1
        return (
          // 점수가 바뀌면 키가 바뀌어 새로 그려지고, 그때 한 번 반짝인다.
          <g
            key={node.member + '@' + String(node.score)}
            className={changed.has(node.member) ? 'node fresh' : 'node'}
            onMouseEnter={() => onHover(column)}
          >
            <rect x={x(column) - 8} y={0} width={COLUMN - 4} height={height} className="hit" />
            {tower(column, node.spans.length, String(column))}
            {arrows(column, node.spans)}
            <text x={x(column) + BOX_WIDTH / 2} y={height - 22} className="member">
              <title>{node.member}</title>
              {shorten(node.member)}
            </text>
            <text x={x(column) + BOX_WIDTH / 2} y={height - 8} className="score">
              {formatScore(node.score)}
            </text>
          </g>
        )
      })}

      {truncated && (
        <text x={x(moreColumn) + BOX_WIDTH / 2} y={y(0) + 13} className="more">
          …
        </text>
      )}
      {Array.from({ length: level }, (_, floor) => (
        <text key={'n' + floor} x={x(nilColumn)} y={y(floor) + 13} className="nil-label">
          nil
        </text>
      ))}
    </svg>
  )
}

/**
 * target 번째 노드의 순위를 셀 때 걷는 길. 서버의 SkipList.rank 와 같은 걸음이다.
 * 위층부터 "건너가도 target 을 넘지 않으면 건넌다"를 반복하고, 못 건너면 한 층 내려간다.
 */
function pathTo(snapshot: SortedSetSnapshot, target: number): Step[] {
  const spanAt = (column: number, floor: number) =>
    column === 0 ? snapshot.header[floor] : (snapshot.nodes[column - 1].spans[floor] ?? -1)

  const steps: Step[] = []
  let at = 0
  for (let floor = snapshot.level - 1; floor >= 0 && at !== target; floor--) {
    for (let span = spanAt(at, floor); span !== -1 && at + span <= target; span = spanAt(at, floor)) {
      steps.push({ from: at, level: floor, span })
      at += span
    }
  }
  return steps
}

function formatScore(score: Score): string {
  return typeof score === 'number' ? String(score) : score
}

function shorten(text: string): string {
  return text.length <= 8 ? text : text.slice(0, 7) + '…'
}
