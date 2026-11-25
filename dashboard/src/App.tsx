import { memo, useState } from 'react'
import { AofPanel } from './components/AofPanel'
import { CommandStream } from './components/CommandStream'
import { ExpiryPanel } from './components/ExpiryPanel'
import { KeyspaceView } from './components/KeyspaceView'
import { RdbPanel } from './components/RdbPanel'
import { ReplicationPanel } from './components/ReplicationPanel'
import { SortedSetView } from './components/SortedSetView'
import { useEventStream } from './useEventStream'
import { useSortedSet } from './useSortedSet'

const STATUS_LABELS = {
  connecting: '연결 중',
  live: '연결됨',
  offline: '끊김 · 다시 붙는 중',
} as const

// 받은 값이 바뀐 패널만 다시 그림 - 활동 100ms·스냅샷 500ms 마다 전체를 그리지 않게
const CommandStreamMemo = memo(CommandStream)
const AofPanelMemo = memo(AofPanel)
const KeyspaceViewMemo = memo(KeyspaceView)
const ExpiryPanelMemo = memo(ExpiryPanel)
const RdbPanelMemo = memo(RdbPanel)
const ReplicationPanelMemo = memo(ReplicationPanel)

export default function App() {
  const dashboard = useEventStream()
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
        <div className="main-col">
          <CommandStreamMemo rows={dashboard.rows} />
          <AofPanelMemo
            aof={dashboard.aof}
            aofEvents={dashboard.aofEvents}
            rewriteStarted={dashboard.rewriteStarted}
            rewriteFinished={dashboard.rewriteFinished}
          />
        </div>
        <div className="side">
          <KeyspaceViewMemo snapshot={dashboard.keyspace} selectedKey={selectedKey} onSelect={setSelectedKey} />
          <ExpiryPanelMemo
            cycles={dashboard.cycles}
            removals={dashboard.removals}
            removalCounts={dashboard.removalCounts}
            keyspace={dashboard.keyspace}
          />
          <RdbPanelMemo rdb={dashboard.rdb} rdbSaves={dashboard.rdbSaves} />
          <ReplicationPanelMemo replication={dashboard.replication} />
        </div>
      </main>

      {selectedKey !== null && <SortedSetView state={sortedSet} onClose={() => setSelectedKey(null)} />}
    </div>
  )
}
