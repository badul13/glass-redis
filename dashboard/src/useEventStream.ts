import { useEffect, useReducer } from 'react'
import type {
  ActivityBatch,
  ActivityEvent,
  ExpiryCycleCompleted,
  KeyRemoved,
  KeyspaceSnapshot,
  RemovalReason,
} from './types'

/** 스트림에 남겨두는 줄 수. 넘으면 오래된 것부터 버린다 — 서버 버퍼와 같은 이유다. */
const MAX_ROWS = 400

/** 만료 패널이 그리는 최근 주기 수. 100ms 주기니까 최근 6초쯤이 보인다. */
const MAX_CYCLES = 60

/** 최근 사라진 키 목록의 길이. */
const MAX_REMOVALS = 40

export type Status = 'connecting' | 'live' | 'offline'

/** 스트림에 그리는 한 줄. 이벤트이거나, 못 받고 지나간 자리를 표시하는 줄이다. */
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

/** 뒤에서부터 limit 개만 남긴다. */
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
        // 끊긴 자리를 눈에 보이게 남긴다. 조용히 넘어가면 화면에 보이는 것이 전부라고 착각하게 된다.
        rows.push({ kind: 'gap', id: events[0]?.seq ?? state.droppedTotal, dropped })
      }

      const cycles = [...state.cycles]
      const removals = [...state.removals]
      const counts = { ...state.removalCounts }
      let commandCount = state.commandCount

      for (const event of events) {
        if (event.type === 'expiryCycle') {
          // 만료 주기는 초당 10번까지 올 수 있다. 스트림에 섞으면 다른 줄이 다 밀려나므로 패널에서만 그린다.
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

/**
 * 서버가 밀어주는 스트림에 붙어서 화면이 그릴 상태를 들고 있는다.
 *
 * <p>EventSource 는 연결이 끊기면 알아서 다시 붙는다. 그래서 재접속 로직을 따로 쓰지 않고
 * 상태 표시만 바꾼다.
 */
export function useEventStream(): Dashboard {
  const [state, dispatch] = useReducer(reduce, EMPTY)

  useEffect(() => {
    const source = new EventSource('/api/stream')

    source.onopen = () => dispatch({ type: 'status', status: 'live' })
    source.onerror = () => dispatch({ type: 'status', status: 'offline' })

    // 이름 붙은 이벤트라서 onmessage 로는 오지 않는다.
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
