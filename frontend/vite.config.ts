import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// En desarrollo, Vite (5173) reenvía la API a Spring (8080): mismo origen para el navegador,
// así que las cookies, el CSRF y WebAuthn funcionan igual que en producción.
// Arranca Spring con CASAAYG_ORIGINS=http://localhost:5173 para las passkeys en modo dev.
const backend = "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: false,
    // Sin data: URIs para fuentes y scripts: todo como archivo, compatible con la CSP 'self'.
    assetsInlineLimit: 0,
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": backend,
      "/login/code": backend,
      "/login/webauthn": backend,
      "/webauthn": backend,
      "/logout": backend,
      "/actuator": backend,
    },
  },
});
