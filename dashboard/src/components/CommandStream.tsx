import { useEffect, useRef } from 'react'
import type { Row } from '../useEventStream'
import { clockTime, duration, millis } from '../format'
import type { ActivityEvent } from '../types'

const REMOVAL_LABELS = {
  LAZY_EXPIRED: '읽다가 만료',
  ACTIVE_EXPIRED: '주기적 만료',
  DELETED: 'DEL',
} as const

/** 바닥 근처일 때만 새 줄 따라 자동 스크롤 */
export function CommandStream({ rows }: { rows: Row[] }) {
  const listRef = useRef<HTMLDivElement>(null)
  const stickToBottom = useRef(true)

  useEffect(() => {
    const list = listRef.current
    if (list && stickToBottom.current) {
      list.scrollTop = list.scrollHeight
    }
  }, [rows])

  function onScroll() {
    const list = listRef.current
    if (!list) return
    const distanceFromBottom = list.scrollHeight - list.scrollTop - list.clientHeight
    stickToBottom.current = distanceFromBottom < 40
  }

  return (
    <section className="panel stream">
      <header className="panel-head">
        <h2>명령 스트림</h2>
        <span className="hint">{rows.length}줄</span>
      </header>
      <div className="stream-rows" ref={listRef} onScroll={onScroll}>
        {rows.length === 0 && (
          <p className="empty">
            아직 아무 일도 일어나지 않았습니다.
            <br />
            <code>redis-cli -p 6380</code> 로 붙어서 명령을 보내보세요.
          </p>
        )}
        {rows.map((row) =>
          row.kind === 'gap' ? (
            <div className="row gap" key={'gap-' + row.id}>
              화면이 못 따라가서 {row.dropped}개를 건너뛰었습니다
            </div>
          ) : (
            <StreamRow key={row.event.seq} event={row.event} />
          ),
        )}
      </div>
    </section>
  )
}

function StreamRow({ event }: { event: ActivityEvent }) {
  return (
    <div className={'row row-' + event.type}>
      <span className="time">{clockTime(event.at)}</span>
      <Body event={event} />
    </div>
  )
}

function Body({ event }: { event: ActivityEvent }) {
  switch (event.type) {
    case 'command':
      return (
        <>
          <span className="conn">#{event.connection}</span>
          <span className="command">
            <b>{event.name}</b> {event.args}
          </span>
          <span className={event.reply.startsWith('-') ? 'reply error' : 'reply'}>{event.reply}</span>
          <span className="took">{duration(event.nanos)}</span>
        </>
      )
    case 'clientConnected':
      return (
        <>
          <span className="conn">#{event.connection}</span>
          <span className="note">접속 {event.peer}</span>
        </>
      )
    case 'clientDisconnected':
      return (
        <>
          <span className="conn">#{event.connection}</span>
          <span className="note">연결 종료</span>
        </>
      )
    case 'keyRemoved':
      return (
        <>
          <span className={'badge reason-' + event.reason}>{REMOVAL_LABELS[event.reason]}</span>
          <span className="command">
            <b>{event.key}</b> 삭제됨
          </span>
          {event.reason !== 'DELETED' && <span className="late">만료보다 {millis(event.lateBy)} 늦게</span>}
        </>
      )
    case 'expiryCycle':
      return null
  }
}
