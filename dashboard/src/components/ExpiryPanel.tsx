import type { Dashboard } from '../useEventStream'
import { millis } from '../format'

/**
 * 만료가 실제로 어떻게 일어나는지.
 *
 * <p>이 프로젝트를 만든 이유가 여기 들어 있다. SET k v EX 10 을 하면 10초 뒤에 키가 사라진다고
 * 알고 있지만, 실제로는 <b>아무도 정확히 10초에 지우지 않는다</b>. 읽으러 온 명령이 발견해서 지우거나,
 * 100ms 마다 도는 샘플링이 우연히 그 키를 뽑아야 지워진다. 그 "늦은 시간"을 숫자로 보여준다.
 */
export function ExpiryPanel({ cycles, removals, removalCounts }: Dashboard) {
  const expiredLate = removals.filter((removal) => removal.reason !== 'DELETED')
  const lateValues = expiredLate.map((removal) => removal.lateBy)
  const averageLate = lateValues.length
    ? Math.round(lateValues.reduce((sum, value) => sum + value, 0) / lateValues.length)
    : null
  const worstLate = lateValues.length ? Math.max(...lateValues) : null

  // 막대 높이는 그 주기에 지운 키 수에 비례한다. 하나도 못 지운 주기도 낮은 막대로 남겨서
  // "돌고는 있었는데 건질 게 없었다"는 사실이 보이게 한다.
  const busiest = Math.max(1, ...cycles.map((cycle) => cycle.expired))

  return (
    <section className="panel expiry">
      <header className="panel-head">
        <h2>만료</h2>
        <span className="hint">샘플링 100ms 주기 · 한 번에 20개</span>
      </header>

      <div className="cycles" title="최근 만료 샘플링 주기. 높을수록 그 주기에 많이 지웠다는 뜻입니다.">
        {cycles.length === 0 ? (
          <span className="empty inline">만료 시각이 걸린 키가 없어 샘플링이 돌지 않습니다</span>
        ) : (
          cycles.map((cycle) => (
            <i
              key={cycle.seq}
              className={cycle.expired > 0 ? 'bar hit' : 'bar'}
              style={{ height: 4 + Math.round((cycle.expired / busiest) * 28) + 'px' }}
              title={cycle.rounds + '바퀴 · ' + cycle.sampled + '개 뽑아 ' + cycle.expired + '개 삭제'}
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
          <dt>샘플링 만료</dt>
          <dd>{removalCounts.ACTIVE_EXPIRED}</dd>
        </div>
        <div>
          <dt>DEL</dt>
          <dd>{removalCounts.DELETED}</dd>
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
                {removal.reason === 'LAZY_EXPIRED' ? '읽다가' : '샘플링'}
              </span>
              <span className="key">{removal.key}</span>
              <span className="late">+{millis(removal.lateBy)}</span>
            </li>
          ))}
      </ul>
    </section>
  )
}
