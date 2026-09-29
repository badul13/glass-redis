import type { Dashboard } from '../useEventStream'
import { millis } from '../format'

/**
 * 만료가 실제로 어떻게 일어나는지.
 *
 * <p>이 프로젝트를 만든 이유가 여기 들어 있다. SET k v EX 10 을 하면 10초 뒤에 키가 사라진다고
 * 알고 있지만, 실제로는 <b>아무도 정확히 10초에 지우지 않는다</b>. 읽으러 온 명령이 발견해서 지우거나,
 * 주기적 만료가 만료 테이블(expires)을 커서로 조금씩 훑다가 그 키에 닿아야 지워진다. 그 "늦은 시간"을 숫자로 보여준다.
 */
export function ExpiryPanel({ cycles, removals, removalCounts, keyspace }: Dashboard) {
  const expiredLate = removals.filter((removal) => removal.reason !== 'DELETED')
  const lateValues = expiredLate.map((removal) => removal.lateBy)
  const averageLate = lateValues.length
    ? Math.round(lateValues.reduce((sum, value) => sum + value, 0) / lateValues.length)
    : null
  const worstLate = lateValues.length ? Math.max(...lateValues) : null

  // 막대 높이는 그 주기에 지운 키 수에 비례한다. 하나도 못 지운 주기도 낮은 막대로 남겨서
  // "돌고는 있었는데 건질 게 없었다"는 사실이 보이게 한다.
  const busiest = Math.max(1, ...cycles.map((cycle) => cycle.expired))
  // 서버가 매 주기 5% 가중치로 섞어 가는 이동 평균이라 마지막 값이 곧 현재 추정치다.
  // 단, 볼 키가 없는 주기는 이벤트가 오지 않아 값이 멈춘다. 만료 대상이 하나도 없으면 보여주지 않는다.
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
