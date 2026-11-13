import { useEffect, useRef } from 'react'
import type { Dashboard } from '../useEventStream'
import { bytes, clockTime } from '../format'
import type { ReplicaView, ReplicationEntry, ReplicationKind, ReplicationSnapshot } from '../types'

const NO_ID = '0'.repeat(40)

const KIND_LABELS: Record<ReplicationKind, string> = {
  sync: '요청',
  full: '전체',
  partial: '부분',
  rdb: 'RDB',
  online: '온라인',
  dropped: '끊김',
  backlog: '백로그',
  id: 'ID',
  link: '링크',
}

const STATE_LABELS: Record<string, string> = {
  wait_bgsave_start: 'RDB 대기',
  wait_bgsave: 'RDB 생성 중',
  send_bulk: 'RDB 전송 중',
  online: '온라인',
  rdb_transmitted: 'RDB 전송 끝',
}

/**
 * 복제 ID·오프셋·백로그 창과 레플리카별 ACK 위치
 * 오프셋 자 - 백로그가 담은 구간 위에 레플리카 ACK 점, 창 밖으로 밀린 레플리카는 끊기면 전체 동기화
 */
export function ReplicationPanel({ replication }: Dashboard) {
  if (replication === null) {
    return null
  }
  const r = replication
  return (
    <section className="panel repl">
      <header className="panel-head">
        <h2>복제</h2>
        <span className="hint">
          {r.role === 'master' ? '마스터' : '레플리카'} · 오프셋 {r.offset.toLocaleString()}
        </span>
      </header>

      <Identity r={r} />
      {r.master && <MasterLink r={r} />}
      <OffsetRuler r={r} />
      <Replicas r={r} />
      <Timeline entries={r.recent} />

      <footer className="repl-foot">
        전체 동기화 {r.syncFull} · 부분 동기화 {r.syncPartialOk} · 거절 {r.syncPartialErr}
        {r.backlog && <> · 버퍼 블록 {r.backlogBlocks} · {bytes(r.bufferMemory)}</>}
        {r.socketTransfer && <span className="busy"> · 디스크리스 RDB 전송 중</span>}
      </footer>
    </section>
  )
}

function shortId(id: string): string {
  return id.slice(0, 8) + '…'
}

/** 복제 ID - 이력의 이름, 두 번째 ID 는 승격 전 이력으로 이어 받을 수 있는 범위 */
function Identity({ r }: { r: ReplicationSnapshot }) {
  return (
    <div className="repl-ids">
      <div title={r.replid}>
        <span className="label">복제 ID</span> <code>{shortId(r.replid)}</code>
      </div>
      {r.replid2 !== NO_ID && (
        <div title={r.replid2}>
          <span className="label">이전 ID</span> <code>{shortId(r.replid2)}</code>
          <span className="dim"> · 오프셋 {(r.secondReplidOffset - 1).toLocaleString()} 까지 이어 받기 가능</span>
        </div>
      )}
    </div>
  )
}

/** 레플리카일 때 - 마스터 링크 상태와 처리 위치 */
function MasterLink({ r }: { r: ReplicationSnapshot }) {
  const m = r.master!
  const up = m.state === 'connected'
  return (
    <div className={'repl-link ' + (up ? 'up' : 'down')}>
      <div>
        <span className="label">마스터</span> <b>{m.host}:{m.port}</b>
        <span className={'badge state-' + m.state}>{m.state}</span>
      </div>
      <div className="dim">
        {up
          ? '처리 위치 ' + m.reploff.toLocaleString() + ' · 마지막 수신 ' + m.lastIoAgo + '초 전'
          : '끊긴 지 ' + m.downSeconds + '초'}
        {m.cachedReplid && (
          <> · 캐시된 마스터 {shortId(m.cachedReplid)}:{m.cachedReploff.toLocaleString()} → PSYNC 로 이어 받기 시도</>
        )}
      </div>
    </div>
  )
}

/** 0 ~ 현재 오프셋 축 위의 백로그 창과 레플리카 ACK 점 */
function OffsetRuler({ r }: { r: ReplicationSnapshot }) {
  if (!r.backlog) {
    return (
      <p className="empty">
        백로그 없음 · 첫 레플리카가 붙을 때 생성{r.role === 'master' && r.offset > 0 ? ' · 그 전 쓰기는 오프셋에만 반영' : ''}
      </p>
    )
  }
  const end = Math.max(1, r.offset)
  const pos = (offset: number) => Math.min(100, Math.max(0, (offset / end) * 100))
  const windowStart = r.backlogFirstByte - 1
  return (
    <div className="repl-ruler" title="막대 전체 = 0 ~ 지금 오프셋 · 밝은 구간 = 백로그가 담은 스트림">
      <div className="track">
        <i className="window" style={{ left: pos(windowStart) + '%', width: pos(r.offset) - pos(windowStart) + '%' }} />
        {r.replicas.map((replica) => (
          <span
            key={replica.id}
            className={'dot ' + (replica.ackOffset < windowStart ? 'outside' : 'inside')}
            style={{ left: pos(replica.ackOffset) + '%' }}
            title={replica.name + ' · ACK ' + replica.ackOffset.toLocaleString()}
          />
        ))}
      </div>
      <div className="scale">
        <span>{windowStart.toLocaleString()}</span>
        <span>
          백로그 {bytes(r.backlogHistlen)} / {bytes(r.backlogSize)}
        </span>
        <span>{r.offset.toLocaleString()}</span>
      </div>
    </div>
  )
}

function Replicas({ r }: { r: ReplicationSnapshot }) {
  if (r.replicas.length === 0) {
    return r.role === 'master' ? <p className="empty">붙은 레플리카 없음</p> : null
  }
  return (
    <table className="repl-replicas">
      <thead>
        <tr>
          <th>레플리카</th>
          <th>상태</th>
          <th className="num">지연</th>
          <th className="num">ACK</th>
          <th className="num">출력 버퍼</th>
        </tr>
      </thead>
      <tbody>
        {r.replicas.map((replica) => (
          <ReplicaRow key={replica.id} replica={replica} r={r} />
        ))}
      </tbody>
    </table>
  )
}

function ReplicaRow({ replica, r }: { replica: ReplicaView; r: ReplicationSnapshot }) {
  const online = replica.state === 'online'
  const lag = online ? r.offset - replica.ackOffset : null
  return (
    <tr>
      <td className="name">{replica.name}</td>
      <td>
        <span className={'badge state-' + replica.state}>{STATE_LABELS[replica.state] ?? replica.state}</span>
        {replica.waitingAck && <span className="dim"> · 첫 ACK 대기</span>}
      </td>
      <td className="num">{lag === null ? '-' : bytes(Math.max(0, lag))}</td>
      <td className="num">{online ? replica.ackAgo + '초 전' : '-'}</td>
      <td className="num">{bytes(replica.outputBytes)}</td>
    </tr>
  )
}

/** 최근 복제 흐름 - 새 기록이 생길 때만 맨 아래로 */
function Timeline({ entries }: { entries: ReplicationEntry[] }) {
  const listRef = useRef<HTMLOListElement>(null)
  const last = entries.at(-1)?.at ?? 0
  useEffect(() => {
    const list = listRef.current
    if (list) {
      list.scrollTop = list.scrollHeight
    }
  }, [entries.length, last])

  if (entries.length === 0) {
    return <p className="empty">복제 기록 없음 · REPLICAOF 또는 레플리카 접속 대기</p>
  }
  return (
    <ol className="repl-events" ref={listRef}>
      {entries.map((event, i) => (
        <li key={event.at + ':' + i} className={'kind-' + event.kind}>
          <span className="time">{clockTime(event.at)}</span>
          <span className="kind">{KIND_LABELS[event.kind]}</span>
          <span className="peer">{event.peer === '-' ? '' : event.peer}</span>
          <span className="detail">{event.detail}</span>
          <span className="offset">{event.offset.toLocaleString()}</span>
        </li>
      ))}
    </ol>
  )
}
