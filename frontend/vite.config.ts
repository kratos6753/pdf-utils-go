import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In development the API is proxied under /v1, so the web app and API share
// an origin (as they do in production behind Caddy) and cookies just work.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/v1': { target: process.env.API_URL ?? 'http://localhost:8080', changeOrigin: false },
    },
  },
})
