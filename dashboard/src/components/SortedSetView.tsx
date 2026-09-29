import { useState } from 'react'
import type { Score, SortedSetSnapshot } from '../types'
import type { SortedSetState } from '../useSortedSet'

const COLUMN = 76
const ROW = 26
const BOX_WIDTH = 30
const BOX_HEIGHT = 18
const PAD_X = 16
const PAD_TOP = 14
/** 맨 아래 멤버 이름과 점수 자리 */
const LABEL_HEIGHT = 38

interface Step {
  from: number
  level: number
  span: number
}

/**
 * 고른 Sorted Set 내부 구조 - listpack 띠 또는 스킵 리스트 그림
 * 스킵 리스트 0번 칸 = 머리 노드
 * 노드 hover 시 순위 계산 경로 강조
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

/** zset-max-listpack-entries, zset-max-listpack-value 기본값 */
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

/** 한도 초과 시 skiplist 전환 - 되돌림 없음 */
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
  // 잘린 경우 너머 화살표용 "…" 칸 추가, 맨 끝 칸은 nil
  const moreColumn = shown + 1
  const nilColumn = shown + (truncated ? 2 : 1)

  const path = hovered === null ? [] : pathTo(snapshot, hovered)
  const walked = new Set(path.map((step) => step.from + ':' + step.level))

  const x = (column: number) => PAD_X + column * COLUMN
  const y = (floor: number) => PAD_TOP + (level - 1 - floor) * ROW
  const width = x(nilColumn) + 40
  const height = PAD_TOP + level * ROW + LABEL_HEIGHT

  // 도착 칸이 화면 밖이면 "…" 칸으로
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
          // 점수 변경 시 key 변경으로 재마운트 - 강조 애니메이션 재생
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

/** 서버 SkipList.rank와 같은 경로 - 지나온 span 합 = 순위 */
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
