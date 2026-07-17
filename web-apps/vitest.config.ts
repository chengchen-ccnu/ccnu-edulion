import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@edu/api-client": fileURLToPath(new URL("./packages/api-client/src/index.ts", import.meta.url)),
      "@edu/auth": fileURLToPath(new URL("./packages/auth/src/index.ts", import.meta.url))
    }
  },
  test: {
    include: ["packages/**/*.test.ts"],
    environment: "node"
  }
});
