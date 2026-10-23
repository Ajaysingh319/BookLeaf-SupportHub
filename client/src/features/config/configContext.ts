import { createContext } from 'react'
import type { Formatters } from '../../lib/format.ts'
import type { ClientConfig } from '../../types/api.ts'

export interface ConfigState {
  config: ClientConfig
  formatters: Formatters
}

export const ConfigContext = createContext<ConfigState | null>(null)
