import type { KeyspaceSnapshot } from '../types'
import { bytes, millis } from '../format'

/** 만료됐지만 아직 안 지워진 키는 stale 표시 */
export function KeyspaceView({
  snapshot,
  selectedKey,
  onSelect,
}: {
  snapshot: KeyspaceSnapshot | null
  selectedKey: string | null
  onSelect: (key: string) => void
}) {
  const keys = snapshot?.keys ?? []
  const hidden = snapshot ? snapshot.total - keys.length : 0

  return (
    <section className="panel keyspace">
      <header className="panel-head">
        <h2>키스페이스</h2>
        <span className="hint">
          {snapshot ? snapshot.total : 0}개 · 만료 대상 {snapshot ? snapshot.expiring : 0}개 · zset 클릭 시 구조 표시
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
              const selectable = key.type === 'zset'
              const classes = [stale && 'stale', selectable && 'selectable', key.key === selectedKey && 'selected']
              return (
                <tr
                  key={key.key}
                  className={classes.filter(Boolean).join(' ') || undefined}
                  onClick={selectable ? () => onSelect(key.key) : undefined}
                >
                  <td className="type">
                    <span className={'type-' + key.type}>{key.type}</span>
                    <span className="encoding">{key.encoding}</span>
                  </td>
                  <td className="key">{key.key}</td>
                  <td className="size">{key.type === 'string' ? bytes(key.size) : key.size + '개'}</td>
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
