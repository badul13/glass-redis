import { CommandStream } from './components/CommandStream'
import { ExpiryPanel } from './components/ExpiryPanel'
import { KeyspaceView } from './components/KeyspaceView'
import { useEventStream } from './useEventStream'

const STATUS_LABELS = {
  connecting: '연결 중',
  live: '연결됨',
  offline: '끊김 · 다시 붙는 중',
} as const

export default function App() {
  const dashboard = useEventStream()

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
          <KeyspaceView snapshot={dashboard.keyspace} />
          <ExpiryPanel {...dashboard} />
        </div>
      </main>
    </div>
  )
}
