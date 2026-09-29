import { useEffect, useState } from 'react'
import type { Score, SortedSetSnapshot } from './types'

export interface SortedSetState {
  snapshot: SortedSetSnapshot
  /** 직전 스냅샷 대비 새로 들어왔거나 점수가 바뀐 멤버 */
  changed: Set<string>
}

/** 키 선택마다 새 연결 - 서버는 변경 시에만 전송 */
export function useSortedSet(key: string | null): SortedSetState | null {
  const [state, setState] = useState<SortedSetState | null>(null)

  useEffect(() => {
    if (key === null) {
      return
    }
    // null이면 첫 스냅샷 - 강조 없음
    let previous: Map<string, Score> | null = null
    const source = new EventSource('/api/zset?key=' + encodeURIComponent(key))

    source.addEventListener('zset', (event) => {
      const snapshot = JSON.parse(event.data) as SortedSetSnapshot
      const before = previous
      const changed = new Set(
        before === null
          ? []
          : snapshot.nodes.filter((node) => before.get(node.member) !== node.score).map((node) => node.member),
      )
      previous = new Map(snapshot.nodes.map((node) => [node.member, node.score]))
      setState({ snapshot, changed })
    })

    return () => {
      source.close()
      setState(null)
    }
  }, [key])

  return state
}
