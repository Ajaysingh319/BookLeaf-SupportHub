import { useContext } from 'react'
import type { Formatters } from '../../lib/format.ts'
import type { ClientConfig } from '../../types/api.ts'
import { ConfigContext } from './configContext.ts'

function useConfigState() {
  const state = useContext(ConfigContext)
  if (!state) throw new Error('useConfig must be used inside <ConfigProvider>.')
  return state
}

/** The server-provided settings (limits, intervals, policy figures, brand, locale). */
export function useConfig(): ClientConfig {
  return useConfigState().config
}

/** Money, number and date formatting for the configured locale and currency. */
export function useFormatters(): Formatters {
  return useConfigState().formatters
}
