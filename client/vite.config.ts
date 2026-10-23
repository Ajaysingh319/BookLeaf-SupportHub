import react from '@vitejs/plugin-react'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

// In development the browser talks only to the Vite server, which forwards /api to the backend.
// The backend address comes from DEV_API_PROXY_TARGET (client/.env). It has no VITE_ prefix on
// purpose, so it is never included in the browser bundle.
export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.DEV_API_PROXY_TARGET
  if (command === 'serve' && !proxyTarget) {
    throw new Error('Set DEV_API_PROXY_TARGET in client/.env (see .env.example), for example the backend address.')
  }

  return {
    plugins: [react()],
    server: proxyTarget ? { proxy: { '/api': proxyTarget } } : {},
    test: {
      environment: 'node',
      include: ['src/**/*.test.ts'],
    },
  }
})
