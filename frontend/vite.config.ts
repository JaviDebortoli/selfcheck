import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In development the api runs on localhost:8080; in Docker, nginx proxies /api (see nginx.conf).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
