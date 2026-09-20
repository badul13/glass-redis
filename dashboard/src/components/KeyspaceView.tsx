import type { KeyspaceSnapshot } from '../types'
import { bytes, millis } from '../format'

/**
 * 지금 키스페이스에 무엇이 들어 있는지.
 *
 * <p>만료 시각이 지났는데 아직 지워지지 않은 키를 숨기지 않는 것이 요점이다.
 * 그 줄이 화면에 남아 있다가 샘플링에 걸려 사라지는 게 이 프로젝트가 보여주려는 장면이다.
 */
export function KeyspaceView({ snapshot }: { snapshot: KeyspaceSnapshot | null }) {
  const keys = snapshot?.keys ?? []
  const hidden = snapshot ? snapshot.total - keys.length : 0

  return (
    <section className="panel keyspace">
      <header className="panel-head">
        <h2>키스페이스</h2>
        <span className="hint">
          {snapshot ? snapshot.total : 0}개 · 만료 대상 {snapshot ? snapshot.expiring : 0}개
        </span>
      </header>

      {keys.length === 0 ? (
        <p className="empty">
          키가 없습니다. <code>SET hello world EX 10</code> 을 해보세요.
        </p>
      ) : (
        <table className="keys">
          <tbody>
            {keys.map((key) => {
              const stale = key.ttl !== null && key.ttl <= 0
              return (
                <tr key={key.key} className={stale ? 'stale' : undefined}>
                  <td className="key">{key.key}</td>
                  <td className="size">{bytes(key.bytes)}</td>
                  <td className="ttl">
                    {key.ttl === null ? (
                      <span className="forever">만료 없음</span>
                    ) : stale ? (
                      <span className="expired">만료됨 · 아직 안 지워짐</span>
                    ) : (
                      <span className={key.ttl < 5_000 ? 'soon' : undefined}>{millis(key.ttl)} 남음</span>
                    )}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}

      {hidden > 0 && <p className="hint footnote">{hidden}개는 목록에서 생략했습니다</p>}
    </section>
  )
}
