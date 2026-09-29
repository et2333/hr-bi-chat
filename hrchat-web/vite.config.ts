import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  define: {
    __APP_VERSION__: JSON.stringify('0.1.0'),
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 不 rewrite 前缀：后端所有 API 前缀为 /api/v1
      },
    },
  },
  test: {
    environment: 'happy-dom',
    // 单测仅覆盖 src，排除 e2e（Playwright 另跑）
    include: ['src/**/*.{test,spec}.?(c|m)[jt]s?(x)'],
  },
})
