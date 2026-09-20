// 서버가 SSE 로 보내는 JSON 의 모양.
// 원본은 Java 쪽 DashboardJson 이고, 그 모양은 DashboardJsonTest 가 못박아 두고 있다.

export type RemovalReason = 'LAZY_EXPIRED' | 'ACTIVE_EXPIRED' | 'DELETED'

interface Stamped {
  /** 발행 순번. 건너뛴 자리가 있으면 그 사이는 버려진 것이다. */
  seq: number
  /** 벌어진 시각(에포크 ms). */
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
  /** 실행 스레드가 이 명령을 붙잡고 있던 시간(ns). 소켓 입출력은 빠져 있다. */
  nanos: number
  reply: string
}

export interface KeyRemoved extends Stamped {
  type: 'keyRemoved'
  key: string
  reason: RemovalReason
  /** 만료 시각이 지나고 실제로 지워지기까지 걸린 시간(ms). DELETED 면 0. */
  lateBy: number
}

export interface ExpiryCycleCompleted extends Stamped {
  type: 'expiryCycle'
  rounds: number
  sampled: number
  expired: number
  nanos: number
}

export type ActivityEvent =
  | ClientConnected
  | ClientDisconnected
  | CommandExecuted
  | KeyRemoved
  | ExpiryCycleCompleted

export interface ActivityBatch {
  /** 이 화면이 못 따라가서 서버가 버린 개수. */
  dropped: number
  events: ActivityEvent[]
}

export interface KeyView {
  key: string
  bytes: number
  /** 만료까지 남은 ms. 만료 시각이 없으면 null, 이미 지났는데 아직 안 지워졌으면 음수. */
  ttl: number | null
}

export interface KeyspaceSnapshot {
  total: number
  expiring: number
  keys: KeyView[]
}
