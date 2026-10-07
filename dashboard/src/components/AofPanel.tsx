import { useEffect, useRef } from 'react'
import type { Dashboard } from '../useEventStream'
import { bytes, duration, millis } from '../format'
import type { AofEvent, AofFile, AofSnapshot } from '../types'

const FILE_LABELS = { b: 'base', h: '지울 파일', i: 'incr' } as const

/** appendonly.aof.2.incr.aof → 2.incr */
function shortName(name: string): string {
  return name.replace(/^appendonly\.aof\./, '').replace(/\.aof$/, '')
}

/**
 * AOF 파일 구성·쓴 명령·fsync·재작성
 * 파일 막대 너비 - 크기 비례, incr 빗금 - fsync 전 바이트
 */
export function AofPanel({ aof, aofEvents, rewriteStarted, rewriteFinished }: Dashboard) {
  if (aof === null) {
    return null
  }
  if (!aof.enabled) {
    return (
      <section className="panel aof">
        <header className="panel-head">
          <h2>AOF</h2>
          <span className="hint">꺼짐</span>
        </header>
        <p className="empty">
          AOF 꺼짐 · <code>--appendonly yes</code> 로 켜면 쓰기 명령이 파일에 쌓이는 과정 표시
        </p>
      </section>
    )
  }

  return (
    <section className="panel aof">
      <header className="panel-head">
        <h2>AOF</h2>
        <span className="hint">
          appendfsync {aof.fsync} · {aof.rewritePercentage > 0
            ? aof.rewritePercentage + '% 증가 + ' + bytes(aof.rewriteMinSize) + ' 초과 시 자동 재작성'
            : '자동 재작성 꺼짐'}
        </span>
      </header>

      <FileStrip aof={aof} />
      <RewriteGauge aof={aof} />
      <AofTail events={aofEvents} />

      <footer className="aof-foot">
        {rewriteStarted && !rewriteFinished && aof.rewriting && (
          <span className="rewriting">
            재작성 중 · 키 {rewriteStarted.keys}개 직렬화 {duration(rewriteStarted.serializeNanos)} · 새 쓰기는{' '}
            {shortName(rewriteStarted.newIncr)} 로
          </span>
        )}
        {rewriteFinished && (
          <span>
            마지막 재작성 · <b>{bytes(rewriteFinished.sizeBefore)}</b> → <b>{bytes(rewriteFinished.sizeAfter)}</b>
            {' · '}
            {duration(rewriteFinished.nanos)}
          </span>
        )}
        {!rewriteStarted && <LoadSummary aof={aof} />}
      </footer>
    </section>
  )
}

function FileStrip({ aof }: { aof: AofSnapshot }) {
  const total = Math.max(1, aof.files.reduce((sum, file) => sum + Math.max(0, file.bytes), 0))
  const current = aof.files.filter((file) => file.type === 'i').at(-1)?.name
  return (
    <div className="aof-files" title="manifest 순서 · 복구 시 base 다음 incr 차례로 재실행">
      {aof.files.map((file) => (
        <FileBar
          key={file.name}
          file={file}
          share={Math.max(0, file.bytes) / total}
          unsynced={file.name === current ? aof.unsyncedBytes : 0}
        />
      ))}
    </div>
  )
}

function FileBar({ file, share, unsynced }: { file: AofFile; share: number; unsynced: number }) {
  // 0바이트 파일도 이름은 보이게 최소 너비
  const unsyncedShare = file.bytes > 0 ? Math.min(1, unsynced / file.bytes) : 0
  return (
    <div className={'aof-file type-' + file.type} style={{ flexGrow: 0.15 + share * 4 }}>
      <div className="bar">
        <i className="unsynced" style={{ width: unsyncedShare * 100 + '%' }} />
      </div>
      <div className="label">
        <span className="kind">{FILE_LABELS[file.type]}</span>
        <span className="name">{shortName(file.name)}</span>
        <span className="size">{file.bytes < 0 ? '?' : bytes(file.bytes)}</span>
      </div>
    </div>
  )
}

/** 자동 재작성 조건 - 전체 > min-size 이고 기준 대비 percentage% 이상 */
function RewriteGauge({ aof }: { aof: AofSnapshot }) {
  if (aof.rewritePercentage <= 0) {
    return null
  }
  const base = Math.max(1, aof.baseSize)
  const target = Math.max(aof.rewriteMinSize + 1, Math.ceil((base * (100 + aof.rewritePercentage)) / 100))
  const progress = Math.min(1, aof.currentSize / target)
  const growth = aof.baseSize > 0 ? Math.round((aof.currentSize * 100) / aof.baseSize - 100) : null
  return (
    <div className="aof-gauge" title={'다음 자동 재작성 목표 ' + bytes(target)}>
      <div className="track">
        <i style={{ width: progress * 100 + '%' }} />
      </div>
      <span className="numbers">
        전체 {bytes(aof.currentSize)} / 목표 {bytes(target)}
        {growth !== null && ' · 재작성 후 +' + Math.max(0, growth) + '%'}
        {aof.unsyncedBytes > 0 && <span className="pending"> · fsync 전 {bytes(aof.unsyncedBytes)}</span>}
        {aof.lastFsyncAgo >= 0 && ' · 마지막 fsync ' + millis(aof.lastFsyncAgo) + ' 전'}
      </span>
    </div>
  )
}

/** 쓴 명령 꼬리 - 새 줄 따라 자동 스크롤 */
function AofTail({ events }: { events: AofEvent[] }) {
  const listRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const list = listRef.current
    if (list) {
      list.scrollTop = list.scrollHeight
    }
  }, [events])

  return (
    <div className="aof-tail" ref={listRef}>
      {events.length === 0 && <span className="empty inline">쓰기 명령 대기 · 읽기 명령은 기록 없음</span>}
      {events.map((event) => (
        <TailEntry key={event.seq} event={event} />
      ))}
    </div>
  )
}

function TailEntry({ event }: { event: AofEvent }) {
  switch (event.type) {
    case 'aofWritten':
      return (
        <div className="aof-write">
          {event.commands.map((command, index) => (
            <div key={index} className={'aof-line ' + lineKind(command)}>
              {command}
            </div>
          ))}
          {event.omitted > 0 && <div className="aof-line meta">… 외 {event.omitted}개</div>}
          <div className="aof-mark write">
            write {bytes(event.bytes)} → {shortName(event.file)} ({bytes(event.fileBytes)})
          </div>
        </div>
      )
    case 'aofFsynced':
      return (
        <div className="aof-mark fsync">
          fsync · {shortName(event.file)} {bytes(event.bytes)}까지 디스크 확정 · {duration(event.nanos)}
        </div>
      )
    case 'aofRewriteStarted':
      return (
        <div className="aof-mark rewrite">
          재작성 시작 · {event.trigger} · 키 {event.keys}개 → 명령 {bytes(event.baseBytes)} · 이후 쓰기는{' '}
          {shortName(event.newIncr)}
        </div>
      )
    case 'aofRewriteFinished':
      return (
        <div className="aof-mark rewrite done">
          재작성 끝 · {bytes(event.sizeBefore)} → {bytes(event.sizeAfter)} · 새 {shortName(event.base)}
          {event.removed.length > 0 && ' · 삭제 ' + event.removed.map(shortName).join(', ')}
        </div>
      )
    case 'aofRewriteFailed':
      return <div className="aof-mark failed">재작성 실패 · {event.reason}</div>
  }
}

/** SELECT·MULTI·EXEC 은 흐리게, DEL 은 만료 전파일 수 있어 따로 */
function lineKind(command: string): string {
  const name = command.split(' ', 1)[0].toUpperCase()
  if (name === 'SELECT' || name === 'MULTI' || name === 'EXEC') return 'meta'
  if (name === 'DEL') return 'del'
  return ''
}

function LoadSummary({ aof }: { aof: AofSnapshot }) {
  switch (aof.loadStatus) {
    case 'OK':
      return (
        <span>
          기동 시 명령 {aof.loadedCommands.toLocaleString()}개 재실행으로 복구 · {millis(aof.loadMillis)}
        </span>
      )
    case 'TRUNCATED':
      return (
        <span className="warn">
          기동 시 복구 · {aof.truncatedFile} 끝의 덜 쓰인 명령 잘라냄 · 명령 {aof.loadedCommands.toLocaleString()}개
        </span>
      )
    default:
      return <span>빈 데이터로 시작 · 파일 없음</span>
  }
}
