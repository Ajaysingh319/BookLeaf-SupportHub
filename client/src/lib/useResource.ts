import { useCallback, useEffect, useState } from 'react'
import { ApiError, toApiError } from './api.ts'

export interface Resource<T> {
  data: T | null
  /** Set when the latest load failed. Earlier data for the same source is kept, so a failed refresh never blanks the page. */
  error: ApiError | null
  /** True until the first result for the current source arrives, and again after reload(). */
  loading: boolean
  lastUpdated: Date | null
  reload: () => void
}

interface State<T> {
  data: T | null
  error: ApiError | null
  lastUpdated: Date | null
  /** Which `load` function and reload request the stored result belongs to. */
  settledFor: { load: unknown; reloadCount: number } | null
}

/**
 * Loads data and optionally keeps it fresh by polling (TDD section 9: short polling, no WebSockets).
 * `load` must be stable (wrap it in useCallback); when it changes (a new page, another ticket id) the
 * previous result is dropped and loading starts again. Polling pauses while the tab is hidden,
 * refreshes once when the tab becomes visible again, and stops when the component unmounts.
 */
export function useResource<T>(load: (signal: AbortSignal) => Promise<T>, intervalMs?: number): Resource<T> {
  const [state, setState] = useState<State<T>>({ data: null, error: null, lastUpdated: null, settledFor: null })
  const [reloadCount, setReloadCount] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    const run = async () => {
      try {
        const data = await load(controller.signal)
        if (active) setState({ data, error: null, lastUpdated: new Date(), settledFor: { load, reloadCount } })
      } catch (error) {
        if (!active || (error instanceof DOMException && error.name === 'AbortError')) return
        setState((previous) => {
          const sameSource = previous.settledFor?.load === load
          return {
            data: sameSource ? previous.data : null,
            error: toApiError(error),
            lastUpdated: sameSource ? previous.lastUpdated : null,
            settledFor: { load, reloadCount },
          }
        })
      }
    }

    void run()

    const refreshIfVisible = () => {
      if (document.visibilityState === 'visible') void run()
    }
    const timer = intervalMs ? window.setInterval(refreshIfVisible, intervalMs) : undefined
    if (intervalMs) document.addEventListener('visibilitychange', refreshIfVisible)

    return () => {
      active = false
      controller.abort()
      if (timer !== undefined) window.clearInterval(timer)
      if (intervalMs) document.removeEventListener('visibilitychange', refreshIfVisible)
    }
  }, [load, intervalMs, reloadCount])

  const reload = useCallback(() => setReloadCount((count) => count + 1), [])

  const current = state.settledFor?.load === load
  const settled = current && state.settledFor?.reloadCount === reloadCount
  return {
    data: current ? state.data : null,
    error: current ? state.error : null,
    loading: !settled,
    lastUpdated: current ? state.lastUpdated : null,
    reload,
  }
}
