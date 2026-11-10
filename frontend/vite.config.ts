import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // Bind IPv4 loopback explicitly: this machine's ::1 is unreachable, so
    // Vite's default "localhost" (resolves to ::1 first) cannot be served.
    host: '127.0.0.1',
    port: 5173,
  },
});
