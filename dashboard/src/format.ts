/** 화면에 숫자를 그리는 방법. 서버는 항상 잰 그대로(ns, ms, byte) 보내고 여기서만 바꾼다. */

export function clockTime(epochMillis: number): string {
  const time = new Date(epochMillis)
  const two = (value: number) => String(value).padStart(2, '0')
  const three = (value: number) => String(value).padStart(3, '0')
  return two(time.getHours()) + ':' + two(time.getMinutes()) + ':' + two(time.getSeconds())
    + '.' + three(time.getMilliseconds())
}

/** 명령 하나가 걸린 시간. 대부분 마이크로초 단위라 단위를 바꿔가며 보여준다. */
export function duration(nanos: number): string {
  if (nanos < 1_000) return nanos + 'ns'
  if (nanos < 1_000_000) return (nanos / 1_000).toFixed(1) + 'µs'
  if (nanos < 1_000_000_000) return (nanos / 1_000_000).toFixed(2) + 'ms'
  return (nanos / 1_000_000_000).toFixed(2) + 's'
}

/** 남은 시간이나 늦은 시간. */
export function millis(value: number): string {
  const absolute = Math.abs(value)
  if (absolute < 1_000) return value + 'ms'
  if (absolute < 60_000) return (value / 1_000).toFixed(1) + '초'
  return (value / 60_000).toFixed(1) + '분'
}

export function bytes(value: number): string {
  if (value < 1024) return value + 'B'
  if (value < 1024 * 1024) return (value / 1024).toFixed(1) + 'KB'
  return (value / (1024 * 1024)).toFixed(1) + 'MB'
}
