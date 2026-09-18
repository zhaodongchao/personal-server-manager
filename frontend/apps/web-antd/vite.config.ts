import { defineConfig } from '@vben/vite-config';

export default defineConfig(async () => {
  return {
    application: {},
    vite: {
      server: {
        proxy: {
          // 后端 REST API（Spring Boot :8080，统一前缀 /api/v1）
          '/api': {
            changeOrigin: true,
            target: 'http://localhost:8080',
            ws: true,
          },
          // 实时监控 WebSocket
          '/ws': {
            changeOrigin: true,
            target: 'http://localhost:8080',
            ws: true,
          },
        },
      },
    },
  };
});
