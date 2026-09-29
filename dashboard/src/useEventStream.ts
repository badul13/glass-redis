import { useEffect, useReducer } from 'react'
import type {
  ActivityBatch,
  ActivityEvent,
  ExpiryCycleCompleted,
  KeyRemoved,
  KeyspaceSnapshot,
  RemovalReason,
} from './types'

/** 초과 시 오래된 줄부터 폐기 */
const MAX_ROWS = 400

/** 100ms 주기 기준 약 6초 분량 */
const MAX_CYCLES = 60

const MAX_REMOVALS = 40

export type Status = 'connecting' | 'live' | 'offline'

/** gap - 서버가 버린 자리 표시 줄 */
export type Row = { kind: 'event'; event: ActivityEvent } | { kind: 'gap'; id: number; dropped: number }

export interface Dashboard {
  status: Status
  rows: Row[]
  keyspace: KeyspaceSnapshot | null
  cycles: ExpiryCycleCompleted[]
  removals: KeyRemoved[]
  removalCounts: Record<RemovalReason, number>
  commandCount: number
  droppedTotal: number
}

const EMPTY: Dashboard = {
  status: 'connecting',
  rows: [],
  keyspace: null,
  cycles: [],
  removals: [],
  removalCounts: { LAZY_EXPIRED: 0, ACTIVE_EXPIRED: 0, DELETED: 0 },
  commandCount: 0,
  droppedTotal: 0,
}

type Action =
  | { type: 'status'; status: Status }
  | { type: 'activity'; batch: ActivityBatch }
  | { type: 'keyspace'; snapshot: KeyspaceSnapshot }

function tail<T>(items: T[], limit: number): T[] {
  return items.length <= limit ? items : items.slice(items.length - limit)
}

function reduce(state: Dashboard, action: Action): Dashboard {
  switch (action.type) {
    case 'status':
      return { ...state, status: action.status }

    case 'keyspace':
      return { ...state, keyspace: action.snapshot }

    case 'activity': {
      const { dropped, events } = action.batch

      const rows = [...state.rows]
      if (dropped > 0) {
        rows.push({ kind: 'gap', id: events[0]?.seq ?? state.droppedTotal, dropped })
      }

      const cycles = [...state.cycles]
      const removals = [...state.removals]
      const counts = { ...state.removalCounts }
      let commandCount = state.commandCount

      for (const event of events) {
        if (event.type === 'expiryCycle') {
          // 초당 최대 10회 수신 - 스트림에 섞으면 다른 줄을 밀어내므로 패널에만 표시
          cycles.push(event)
          continue
        }
        rows.push({ kind: 'event', event })
        if (event.type === 'command') {
          commandCount += 1
        } else if (event.type === 'keyRemoved') {
          removals.push(event)
          counts[event.reason] += 1
        }
      }

      return {
        ...state,
        rows: tail(rows, MAX_ROWS),
        cycles: tail(cycles, MAX_CYCLES),
        removals: tail(removals, MAX_REMOVALS),
        removalCounts: counts,
        commandCount,
        droppedTotal: state.droppedTotal + dropped,
      }
    }
  }
}

/** 재접속은 EventSource 자동 처리 - 상태 표시만 갱신 */
export function useEventStream(): Dashboard {
  const [state, dispatch] = useReducer(reduce, EMPTY)

  useEffect(() => {
    const source = new EventSource('/api/stream')

    source.onopen = () => dispatch({ type: 'status', status: 'live' })
    source.onerror = () => dispatch({ type: 'status', status: 'offline' })

    // 이름 붙은 이벤트는 onmessage 수신 대상 아님
    source.addEventListener('activity', (event) => {
      dispatch({ type: 'activity', batch: JSON.parse(event.data) as ActivityBatch })
    })
    source.addEventListener('keyspace', (event) => {
      dispatch({ type: 'keyspace', snapshot: JSON.parse(event.data) as KeyspaceSnapshot })
    })

    return () => source.close()
  }, [])

  return state
}
