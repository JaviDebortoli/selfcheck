import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In development the api runs on localhost:8080 and the simulator on 8081; in Docker nginx proxies both (nginx.conf).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api/simulator': 'http://localhost:8081',
      '/api': 'http://localhost:8080',
    },
  },
})
