import type { Dashboard } from '../useEventStream'
import { millis } from '../format'

/** 만료 주기 + 만료 시각부터 실제 삭제까지 지연 */
export function ExpiryPanel({ cycles, removals, removalCounts, keyspace }: Dashboard) {
  const expiredLate = removals.filter((removal) => removal.reason !== 'DELETED')
  const lateValues = expiredLate.map((removal) => removal.lateBy)
  const averageLate = lateValues.length
    ? Math.round(lateValues.reduce((sum, value) => sum + value, 0) / lateValues.length)
    : null
  const worstLate = lateValues.length ? Math.max(...lateValues) : null

  // 막대 높이 - 지운 키 수 비례, 0개 주기도 최소 높이 유지
  const busiest = Math.max(1, ...cycles.map((cycle) => cycle.expired))
  // 서버 이동 평균 - 마지막 값이 현재 추정치
  // 만료 대상 없으면 값 정지 - 숨김 처리
  const staleEstimate = cycles.length && keyspace?.expiring ? cycles[cycles.length - 1].stalePercent : null

  return (
    <section className="panel expiry">
      <header className="panel-head">
        <h2>만료</h2>
        <span className="hint">SLOW 100ms마다 최대 25ms · FAST 쉬기 직전 최대 1ms · 20개씩 훑기</span>
      </header>

      <div className="cycles" title="최근 주기적 만료 · 막대 높이: 지운 키 수 · 파랑: FAST · 테두리: 시간 한도 도달">
        {cycles.length === 0 ? (
          <span className="empty inline">만료 시각이 걸린 키 없음 · 주기적 만료 대기</span>
        ) : (
          cycles.map((cycle) => (
            <i
              key={cycle.seq}
              className={['bar', cycle.expired > 0 && 'hit', cycle.kind === 'FAST' && 'fast', cycle.timeLimitHit && 'limit']
                .filter(Boolean)
                .join(' ')}
              style={{ height: 4 + Math.round((cycle.expired / busiest) * 28) + 'px' }}
              title={cycle.kind + ' · ' + cycle.rounds + '바퀴 · ' + cycle.sampled + '개 확인 · ' + cycle.expired + '개 삭제'
                + (cycle.timeLimitHit ? ' · 시간 한도 도달' : '')}
            />
          ))
        )}
      </div>

      <dl className="stats">
        <div>
          <dt>읽다가 만료</dt>
          <dd>{removalCounts.LAZY_EXPIRED}</dd>
        </div>
        <div>
          <dt>주기적 만료</dt>
          <dd>{removalCounts.ACTIVE_EXPIRED}</dd>
        </div>
        <div>
          <dt>DEL</dt>
          <dd>{removalCounts.DELETED}</dd>
        </div>
        <div title="만료됐는데 메모리에 남은 키의 비율 추정치 · 10% 초과 시 FAST 만료 가동">
          <dt>남은 만료 키 추정</dt>
          <dd>{staleEstimate === null ? '-' : staleEstimate + '%'}</dd>
        </div>
      </dl>

      {averageLate !== null && worstLate !== null && (
        <p className="verdict">
          만료 시각이 지나고 실제로 지워지기까지 평균 <b>{millis(averageLate)}</b>, 최대{' '}
          <b>{millis(worstLate)}</b> 걸렸습니다.
        </p>
      )}

      <ul className="removals">
        {expiredLate
          .slice()
          .reverse()
          .slice(0, 8)
          .map((removal) => (
            <li key={removal.seq}>
              <span className={'badge reason-' + removal.reason}>
                {removal.reason === 'LAZY_EXPIRED' ? '읽다가' : '주기적'}
              </span>
              <span className="key">{removal.key}</span>
              <span className="late">+{millis(removal.lateBy)}</span>
            </li>
          ))}
      </ul>
    </section>
  )
}
