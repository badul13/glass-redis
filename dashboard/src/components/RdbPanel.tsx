import { useEffect, useRef } from 'react'
import type { Dashboard, RdbSave } from '../useEventStream'
import { bytes, clockTime, duration, millis } from '../format'
import type { RdbSnapshot, SaveRule } from '../types'

/**
 * save 규칙 판정·저장 기록·MISCONF
 * 규칙 막대 - serverCron 판정 그대로: 지난 초 > seconds 이고 dirty >= changes
 * 저장 막대 - 명령 대기(직렬화) · 쓰기 · 완료 확인까지
 */
export function RdbPanel({ rdb, rdbSaves }: Dashboard) {
  if (rdb === null) {
    return null
  }

  return (
    <section className="panel rdb">
      <header className="panel-head">
        <h2>RDB</h2>
        <span className="hint">
          {rdb.file} · {rdb.fileBytes < 0 ? '파일 없음' : bytes(rdb.fileBytes)} · 저장 {rdb.saves}회
        </span>
      </header>

      {rdb.deniesWrites && (
        <div className="rdb-misconf">
          <b>MISCONF</b> 마지막 BGSAVE 실패 · 쓰기 명령 거부 중 (stop-writes-on-bgsave-error) · 다음 저장 성공 시 해제
        </div>
      )}

      <Rules rdb={rdb} />
      <Saves saves={rdbSaves} saving={rdb.saving} />

      <footer className="rdb-foot">
        <LoadSummary rdb={rdb} />
      </footer>
    </section>
  )
}

/** 저장이 미뤄지는 이유 - 판정 순서는 Persistence.cron */
function holdReason(rdb: RdbSnapshot): string | null {
  if (rdb.saving) return '저장 중 · 끝난 뒤 다시 판정'
  if (rdb.otherChild) return 'AOF 재작성 중 · 백그라운드 작업은 한 번에 하나'
  if (rdb.retryIn > 0) return '실패 직후 · 재시도 ' + rdb.retryIn + '초 뒤'
  return null
}

function Rules({ rdb }: { rdb: RdbSnapshot }) {
  if (rdb.rules.length === 0) {
    return (
      <p className="empty">
        save 규칙 없음 · 자동 저장·종료 시 저장 꺼짐 · <code>SAVE</code>·<code>BGSAVE</code> 만
      </p>
    )
  }
  const hold = holdReason(rdb)
  return (
    <div className="rdb-rules" title="serverCron 이 100ms마다 위에서부터 확인 · 두 막대가 다 차면 BGSAVE">
      <div className="rdb-summary">
        마지막 저장 뒤 <b>{rdb.sinceSave.toLocaleString()}초</b> · 변경 <b>{rdb.dirty.toLocaleString()}</b>번
        {rdb.saving && rdb.dirty > rdb.savingDirty && (
          <span className="pending"> · 저장 중 들어온 변경 {(rdb.dirty - rdb.savingDirty).toLocaleString()}</span>
        )}
        {rdb.scheduled && <span className="pending"> · 예약된 BGSAVE</span>}
        {hold && <span className="hold"> · {hold}</span>}
      </div>
      {rdb.rules.map((rule) => (
        <RuleRow key={rule.seconds + ':' + rule.changes} rule={rule} rdb={rdb} />
      ))}
    </div>
  )
}

function RuleRow({ rule, rdb }: { rule: SaveRule; rdb: RdbSnapshot }) {
  const timeMet = rdb.sinceSave > rule.seconds
  const changesMet = rdb.dirty >= rule.changes
  const fired = rdb.saving && rdb.savingTrigger === 'save ' + rule.seconds + ' ' + rule.changes
  const timeShare = Math.min(1, rdb.sinceSave / rule.seconds)
  // changes 0 - 시간만 보는 규칙
  const changeShare = rule.changes === 0 ? 1 : Math.min(1, rdb.dirty / rule.changes)
  const state = fired ? 'fired' : timeMet && changesMet ? 'met' : ''
  return (
    <div className={'rdb-rule ' + state}>
      <span className="name">
        save {rule.seconds} {rule.changes}
      </span>
      <Meter share={timeShare} met={timeMet} label={rdb.sinceSave.toLocaleString() + ' / ' + rule.seconds.toLocaleString() + '초'} />
      <Meter share={changeShare} met={changesMet} label={rdb.dirty.toLocaleString() + ' / ' + rule.changes.toLocaleString() + '번'} />
      <span className="rule-state">{fired ? '이 규칙으로 저장 중' : timeMet && changesMet ? '충족' : timeMet ? '변경 대기' : changesMet ? '시간 대기' : ''}</span>
    </div>
  )
}

function Meter({ share, met, label }: { share: number; met: boolean; label: string }) {
  return (
    <span className={'rdb-meter' + (met ? ' met' : '')}>
      <span className="track">
        <i style={{ width: share * 100 + '%' }} />
      </span>
      <span className="label">{label}</span>
    </span>
  )
}

/** 최근 저장 - 막대 길이는 목록 안 최장 저장 기준, 새 기록 따라 자동 스크롤 */
function Saves({ saves, saving }: { saves: RdbSave[]; saving: boolean }) {
  const listRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const list = listRef.current
    if (list) {
      list.scrollTop = list.scrollHeight
    }
  }, [saves])

  if (saves.length === 0) {
    return <div className="rdb-saves"><span className="empty inline">저장 기록 없음 · SAVE·BGSAVE 또는 규칙 충족 대기</span></div>
  }
  const longest = Math.max(1, ...saves.map(totalNanos))
  return (
    <div className="rdb-saves" ref={listRef} title="주황: 실행 스레드가 명령을 못 받는 구간 · 파랑: 백그라운드 쓰기 · 회색: serverCron 이 끝을 알아채기까지">
      {saves.map((save, index) => (
        <SaveRow
          key={save.started.seq}
          save={save}
          longest={longest}
          running={saving && index === saves.length - 1 && !save.finished && !save.failed}
        />
      ))}
    </div>
  )
}

function totalNanos(save: RdbSave): number {
  return save.finished ? save.finished.nanos : save.started.serializeNanos
}

function SaveRow({ save, longest, running }: { save: RdbSave; longest: number; running: boolean }) {
  const { started, finished, failed } = save
  const total = totalNanos(save)
  const serialize = started.serializeNanos
  const write = finished ? finished.writeNanos : 0
  const notice = finished ? Math.max(0, total - serialize - write) : 0
  const scale = (nanos: number) => (nanos / longest) * 100 + '%'
  // SAVE - 쓰기까지 실행 스레드에서, 전부 명령 대기
  const writeKind = started.background ? 'write' : 'block'

  return (
    <div className={'rdb-save' + (failed ? ' failed' : '') + (running ? ' running' : '')}>
      <div className="line">
        <span className="time">{clockTime(started.at)}</span>
        <span className="trigger">{started.trigger}</span>
        <span className="track">
          <i className="block" style={{ width: scale(serialize) }} />
          {finished && <i className={writeKind} style={{ width: scale(write) }} />}
          {finished && notice > 0 && <i className="notice" style={{ width: scale(notice) }} />}
        </span>
        <span className="total">{finished ? duration(total) : failed ? '실패' : '쓰는 중'}</span>
      </div>
      <div className="detail" title={failed?.reason}>
        키 {started.keys.toLocaleString()} · {bytes(started.bytes)} · 명령 대기{' '}
        {duration(started.background ? serialize : serialize + write)}
        {finished && started.background && ' · 쓰기 ' + duration(write)}
        {finished && ' · 변경 ' + finished.saved.toLocaleString() + ' 반영'}
        {finished && finished.remaining > 0 && (
          <span className="pending"> · 저장 중 변경 {finished.remaining.toLocaleString()} 다음 저장으로</span>
        )}
        {failed && <span className="error"> · {failed.reason}</span>}
      </div>
    </div>
  )
}

function LoadSummary({ rdb }: { rdb: RdbSnapshot }) {
  switch (rdb.loadStatus) {
    case 'OK':
      return (
        <span>
          기동 시 {rdb.file} 에서 키 <b>{rdb.loadedKeys.toLocaleString()}</b>개 복구
          {rdb.loadedExpired > 0 && ' · 지난 만료 ' + rdb.loadedExpired.toLocaleString() + '개 버림'}
          {' · '}
          {bytes(rdb.loadBytes)} · {millis(rdb.loadMillis)}
        </span>
      )
    case 'AOF':
      return <span>기동 시 AOF 로 복구 · AOF 가 켜져 있으면 RDB 안 읽음</span>
    case 'FAILED':
      return <span className="error">기동 시 RDB 복구 실패</span>
    default:
      return <span>기동 시 {rdb.file} 없음 · 빈 데이터로 시작</span>
  }
}
