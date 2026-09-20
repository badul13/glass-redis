import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 개발 중에는 이 서버(5173)가 화면을 주고, 데이터는 glass-redis 가 띄운 대시보드 서버(8080)에서 온다.
// 프록시로 같은 출처처럼 보이게 하면 CORS 설정이 필요 없고, 화면 코드도 배포판과 똑같이 '/api/stream' 만 부른다.
const GLASS_REDIS = 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': GLASS_REDIS,
    },
  },
})
