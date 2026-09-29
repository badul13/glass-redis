import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 서버 /api 프록시 - 같은 출처라 CORS 불필요
const GLASS_REDIS = 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': GLASS_REDIS,
    },
  },
})
