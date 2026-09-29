// 서버 DashboardJson 출력 모양 - DashboardJsonTest로 고정

export type RemovalReason = 'LAZY_EXPIRED' | 'ACTIVE_EXPIRED' | 'DELETED'

interface Stamped {
  /** 건너뛴 번호는 버려진 이벤트 */
  seq: number
  /** 에포크 ms */
  at: number
}

export interface ClientConnected extends Stamped {
  type: 'clientConnected'
  connection: number
  peer: string
}

export interface ClientDisconnected extends Stamped {
  type: 'clientDisconnected'
  connection: number
}

export interface CommandExecuted extends Stamped {
  type: 'command'
  connection: number
  name: string
  args: string
  /** 실행 스레드 소요 ns - 소켓 입출력 제외 */
  nanos: number
  reply: string
}

export interface KeyRemoved extends Stamped {
  type: 'keyRemoved'
  key: string
  reason: RemovalReason
  /** 만료 시각부터 실제 삭제까지 ms - DELETED면 0 */
  lateBy: number
}

export interface ExpiryCycleCompleted extends Stamped {
  type: 'expiryCycle'
  /** SLOW - 100ms 주기, 최대 25ms / FAST - 큐가 비어 쉬기 직전, 최대 1ms */
  kind: 'SLOW' | 'FAST'
  /** 10% 규칙 반복 바퀴 수 */
  rounds: number
  sampled: number
  expired: number
  nanos: number
  timeLimitHit: boolean
  /** 만료됐지만 남아 있는 키의 비율 추정치(%) */
  stalePercent: number
}

export type ActivityEvent =
  | ClientConnected
  | ClientDisconnected
  | CommandExecuted
  | KeyRemoved
  | ExpiryCycleCompleted

export interface ActivityBatch {
  /** 화면이 못 따라가 서버가 버린 개수 */
  dropped: number
  events: ActivityEvent[]
}

export type ValueType = 'string' | 'list' | 'hash' | 'set' | 'zset'

export interface KeyView {
  key: string
  type: ValueType
  /** OBJECT ENCODING 값 */
  encoding: string
  /** 문자열은 바이트 수, 모음은 원소 수 */
  size: number
  /** 남은 ms - 만료 시각 없으면 null, 만료됐지만 아직 안 지워졌으면 음수 */
  ttl: number | null
}

export interface KeyspaceSnapshot {
  total: number
  expiring: number
  keys: KeyView[]
}

/** JSON에 무한대 없음 - 문자열로 수신 */
export type Score = number | 'inf' | '-inf'

export interface SortedSetMember {
  member: string
  score: Score
  /** 층별 span - 그 층 마지막이면 -1, 길이 = 층수, listpack이면 빈 배열 */
  spans: number[]
}

export interface SortedSetSnapshot {
  key: string
  status: 'ok' | 'missing' | 'wrongType'
  encoding: 'listpack' | 'skiplist' | ''
  /** 전체 멤버 수 - nodes가 잘려도 전체 기준 */
  length: number
  /** listpack 바이트 크기 - skiplist면 0 */
  bytes: number
  level: number
  /** 머리 노드의 층별 span */
  header: number[]
  nodes: SortedSetMember[]
}
