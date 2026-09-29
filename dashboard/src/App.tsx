import { useState } from 'react'
import { CommandStream } from './components/CommandStream'
import { ExpiryPanel } from './components/ExpiryPanel'
import { KeyspaceView } from './components/KeyspaceView'
import { SortedSetView } from './components/SortedSetView'
import { useEventStream } from './useEventStream'
import { useSortedSet } from './useSortedSet'

const STATUS_LABELS = {
  connecting: '연결 중',
  live: '연결됨',
  offline: '끊김 · 다시 붙는 중',
} as const

export default function App() {
  const dashboard = useEventStream()
  // 키 목록에서 고른 Sorted Set. 고르면 아래에 스킵 리스트 패널이 열린다.
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const sortedSet = useSortedSet(selectedKey)

  return (
    <div className="app">
      <header className="top">
        <h1>
          glass-redis<span className="sub">동작이 보이는 Redis</span>
        </h1>
        <div className="top-stats">
          <span>명령 {dashboard.commandCount.toLocaleString()}개</span>
          {dashboard.droppedTotal > 0 && (
            <span className="dropped">생략 {dashboard.droppedTotal.toLocaleString()}개</span>
          )}
          <span className={'status status-' + dashboard.status}>{STATUS_LABELS[dashboard.status]}</span>
        </div>
      </header>

      <main className="grid">
        <CommandStream rows={dashboard.rows} />
        <div className="side">
          <KeyspaceView snapshot={dashboard.keyspace} selectedKey={selectedKey} onSelect={setSelectedKey} />
          <ExpiryPanel {...dashboard} />
        </div>
      </main>

      {selectedKey !== null && <SortedSetView state={sortedSet} onClose={() => setSelectedKey(null)} />}
    </div>
  )
}
