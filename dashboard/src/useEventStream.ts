import { useEffect, useReducer } from 'react'
import type {
  ActivityBatch,
  ActivityEvent,
  AofEvent,
  AofRewriteFinished,
  AofRewriteStarted,
  AofSnapshot,
  ExpiryCycleCompleted,
  KeyRemoved,
  KeyspaceSnapshot,
  RdbSaveFailed,
  RdbSaveFinished,
  RdbSaveStarted,
  RdbSnapshot,
  RemovalReason,
  ReplicationSnapshot,
} from './types'

/** 초과 시 오래된 줄부터 폐기 */
const MAX_ROWS = 400

/** 100ms 주기 기준 약 6초 분량 */
const MAX_CYCLES = 60

const MAX_REMOVALS = 40

/** AOF 꼬리 - 쓰기 묶음·fsync·재작성 표시 */
const MAX_AOF_EVENTS = 120

/** RDB 저장 기록 - 최근 것만 */
const MAX_RDB_SAVES = 8

export type Status = 'connecting' | 'live' | 'offline'

/** gap - 서버가 버린 자리 표시 줄 */
export type Row = { kind: 'event'; event: ActivityEvent } | { kind: 'gap'; id: number; dropped: number }

/** 저장 한 번 - 시작 이벤트에 끝 또는 실패를 붙임 */
export interface RdbSave {
  started: RdbSaveStarted
  finished: RdbSaveFinished | null
  failed: RdbSaveFailed | null
}

export interface Dashboard {
  status: Status
  rows: Row[]
  keyspace: KeyspaceSnapshot | null
  cycles: ExpiryCycleCompleted[]
  removals: KeyRemoved[]
  removalCounts: Record<RemovalReason, number>
  commandCount: number
  droppedTotal: number
  aof: AofSnapshot | null
  aofEvents: AofEvent[]
  /** 진행 중이거나 마지막 재작성 */
  rewriteStarted: AofRewriteStarted | null
  rewriteFinished: AofRewriteFinished | null
  rdb: RdbSnapshot | null
  rdbSaves: RdbSave[]
  replication: ReplicationSnapshot | null
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
  aof: null,
  aofEvents: [],
  rewriteStarted: null,
  rewriteFinished: null,
  rdb: null,
  rdbSaves: [],
  replication: null,
}

type Action =
  | { type: 'status'; status: Status }
  | { type: 'connected' }
  | { type: 'activity'; batch: ActivityBatch }
  | { type: 'keyspace'; snapshot: KeyspaceSnapshot }
  | { type: 'aof'; snapshot: AofSnapshot }
  | { type: 'rdb'; snapshot: RdbSnapshot }
  | { type: 'replication'; snapshot: ReplicationSnapshot }

function tail<T>(items: T[], limit: number): T[] {
  return items.length <= limit ? items : items.slice(items.length - limit)
}

function reduce(state: Dashboard, action: Action): Dashboard {
  switch (action.type) {
    case 'status':
      return { ...state, status: action.status }

    case 'keyspace':
      return { ...state, keyspace: action.snapshot }

    case 'aof':
      return { ...state, aof: action.snapshot }

    case 'rdb':
      return { ...state, rdb: action.snapshot }

    case 'replication':
      return { ...state, replication: action.snapshot }

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
      const aofEvents = [...state.aofEvents]
      let { rewriteStarted, rewriteFinished } = state
      const rdbSaves = [...state.rdbSaves]
      let rdbChanged = false

      for (const event of events) {
        if (event.type === 'expiryCycle') {
          // 초당 최대 10회 수신 - 스트림에 섞으면 다른 줄을 밀어내므로 패널에만 표시
          cycles.push(event)
          continue
        }
        if (event.type.startsWith('aof')) {
          // 명령마다 생겨 스트림을 밀어내므로 AOF 패널에만
          aofEvents.push(event as AofEvent)
          if (event.type === 'aofRewriteStarted') {
            rewriteStarted = event
            rewriteFinished = null
          } else if (event.type === 'aofRewriteFinished') {
            rewriteFinished = event
          }
          continue
        }
        if (event.type.startsWith('rdb')) {
          // 저장은 한 번에 하나 - 끝·실패는 마지막 기록에 붙임
          if (event.type === 'rdbSaveStarted') {
            rdbSaves.push({ started: event, finished: null, failed: null })
            rdbChanged = true
          } else {
            const last = rdbSaves.at(-1)
            if (last && !last.finished && !last.failed) {
              rdbSaves[rdbSaves.length - 1] = event.type === 'rdbSaveFinished'
                ? { ...last, finished: event }
                : { ...last, failed: event as RdbSaveFailed }
              rdbChanged = true
            }
          }
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

      // 안 바뀐 목록은 이전 참조 그대로 - memo 패널이 다시 그리지 않게
      const removalsChanged = removals.length !== state.removals.length
      return {
        ...state,
        rows: rows.length !== state.rows.length ? tail(rows, MAX_ROWS) : state.rows,
        cycles: cycles.length !== state.cycles.length ? tail(cycles, MAX_CYCLES) : state.cycles,
        removals: removalsChanged ? tail(removals, MAX_REMOVALS) : state.removals,
        removalCounts: removalsChanged ? counts : state.removalCounts,
        commandCount,
        droppedTotal: state.droppedTotal + dropped,
        aofEvents: aofEvents.length !== state.aofEvents.length ? tail(aofEvents, MAX_AOF_EVENTS) : state.aofEvents,
        rewriteStarted,
        rewriteFinished,
        rdbSaves: rdbChanged ? tail(rdbSaves, MAX_RDB_SAVES) : state.rdbSaves,
      }
    }

    case 'connected':
      // 새 연결 - 서버 재시작이면 seq 가 0부터라 이전 줄과 key 충돌, 스냅샷은 곧 다시 옴
      return { ...EMPTY, status: 'live' }
  }
}

/** CLOSED 뒤 새 연결까지 대기 */
export const RECONNECT_MILLIS = 2000

/**
 * 끊김 시 다시 연결하는 EventSource
 * 네트워크 오류는 EventSource 자동 재접속, 200 이 아닌 응답(재시작 중 503 등)은 CLOSED 로 끝 - 이때 직접 새로 만듦
 * @returns 정리 함수
 */
export function openStream(
  url: string,
  listeners: Record<string, (data: string) => void>,
  onStatus: (status: 'open' | 'error') => void,
): () => void {
  let source: EventSource | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  let disposed = false

  const connect = () => {
    source = new EventSource(url)
    source.onopen = () => onStatus('open')
    source.onerror = () => {
      onStatus('error')
      if (source?.readyState === EventSource.CLOSED && !disposed) {
        timer = setTimeout(connect, RECONNECT_MILLIS)
      }
    }
    // 이름 붙은 이벤트는 onmessage 수신 대상 아님
    for (const [name, handle] of Object.entries(listeners)) {
      source.addEventListener(name, (event) => handle((event as MessageEvent<string>).data))
    }
  }
  connect()

  return () => {
    disposed = true
    if (timer !== null) {
      clearTimeout(timer)
    }
    source?.close()
  }
}

export function useEventStream(): Dashboard {
  const [state, dispatch] = useReducer(reduce, EMPTY)

  useEffect(() => {
    // 스냅샷은 500ms 마다 같은 내용으로도 옴 - 직전 원문과 같으면 반영 생략 (패널 재렌더 방지)
    const last: Record<string, string> = {}
    const changed = (name: string, data: string) => {
      if (last[name] === data) {
        return false
      }
      last[name] = data
      return true
    }
    return openStream(
      '/api/stream',
      {
        activity: (data) => dispatch({ type: 'activity', batch: JSON.parse(data) as ActivityBatch }),
        keyspace: (data) =>
          changed('keyspace', data) && dispatch({ type: 'keyspace', snapshot: JSON.parse(data) as KeyspaceSnapshot }),
        aof: (data) => changed('aof', data) && dispatch({ type: 'aof', snapshot: JSON.parse(data) as AofSnapshot }),
        rdb: (data) => changed('rdb', data) && dispatch({ type: 'rdb', snapshot: JSON.parse(data) as RdbSnapshot }),
        replication: (data) =>
          changed('replication', data) &&
          dispatch({ type: 'replication', snapshot: JSON.parse(data) as ReplicationSnapshot }),
      },
      (status) => {
        if (status === 'open') {
          // 새 연결 - 상태를 비웠으니 첫 스냅샷은 같은 내용이어도 반영
          for (const name of Object.keys(last)) {
            delete last[name]
          }
          dispatch({ type: 'connected' })
        } else {
          dispatch({ type: 'status', status: 'offline' })
        }
      },
    )
  }, [])

  return state
}
