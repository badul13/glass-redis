import { useEffect, useState } from 'react'
import type { Score, SortedSetSnapshot } from './types'

export interface SortedSetState {
  snapshot: SortedSetSnapshot
  /** 직전 모양과 비교해 새로 들어왔거나 점수가 바뀐 멤버. 화면에서 반짝이게 한다. */
  changed: Set<string>
}

/**
 * 고른 Sorted Set 하나의 스킵 리스트 모양을 받아 온다.
 *
 * <p>키 목록과 달리 키를 골랐을 때만 필요해서 연결을 따로 연다. 다른 키를 고르면 이 연결을 닫고 새로 연다.
 * 서버는 모양이 바뀔 때만 보내므로, 메시지가 왔다는 것 자체가 "뭔가 바뀌었다"는 뜻이다.
 */
export function useSortedSet(key: string | null): SortedSetState | null {
  const [state, setState] = useState<SortedSetState | null>(null)

  useEffect(() => {
    if (key === null) {
      return
    }
    // 처음 받은 모양에서는 아무것도 반짝이지 않게, 이전 모양이 없다는 걸 null 로 구분한다.
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
