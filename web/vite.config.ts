import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // lane D globs ../docs/perf (outside web/) for the dev portal's charts
    fs: { allow: ['..'] },
    proxy: {
      '/api': {
        target: 'http://localhost:8088',
        changeOrigin: true,
        ws: true,
        // SSE: keep the connection open, don't buffer the stream
        proxyTimeout: 0,
        timeout: 0,
      },
    },
  },
})
