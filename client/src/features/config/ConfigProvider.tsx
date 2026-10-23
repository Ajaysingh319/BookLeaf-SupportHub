import { useCallback, useEffect, useMemo, type ReactNode } from 'react'
import { ErrorMessage, Spinner } from '../../components/feedback.tsx'
import { api } from '../../lib/api.ts'
import { createFormatters } from '../../lib/format.ts'
import { useResource } from '../../lib/useResource.ts'
import { ConfigContext, type ConfigState } from './configContext.ts'

/**
 * Loads the server's settings once at startup and shares them. The app cannot work without them
 * (poll intervals, limits, locale), so a failure shows a retry instead of falling back to guesses.
 */
export function ConfigProvider({ children }: { children: ReactNode }) {
  const load = useCallback((signal: AbortSignal) => api.getConfig(signal), [])
  const resource = useResource(load)
  const config = resource.data

  const value = useMemo<ConfigState | null>(
    () => (config ? { config, formatters: createFormatters(config) } : null),
    [config],
  )

  useEffect(() => {
    if (config) document.title = config.brandName
  }, [config])

  if (!value) {
    if (resource.error) {
      return (
        <main className="container main">
          <ErrorMessage error={resource.error} onRetry={resource.reload} />
        </main>
      )
    }
    return <Spinner label="Loading…" />
  }
  return <ConfigContext.Provider value={value}>{children}</ConfigContext.Provider>
}
