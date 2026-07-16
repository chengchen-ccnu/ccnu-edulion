import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath } from "node:url";
const workspaceAlias = {
  "@edu/design-tokens/tokens.css": fileURLToPath(new URL("../../packages/design-tokens/tokens.css", import.meta.url)),
  "@edu/ui/shell.css": fileURLToPath(new URL("../../packages/ui/src/shell.css", import.meta.url)),
  "@edu/api-client": fileURLToPath(new URL("../../packages/api-client/src/index.ts", import.meta.url)),
  "@edu/auth": fileURLToPath(new URL("../../packages/auth/src/index.ts", import.meta.url)),
  "@edu/design-tokens": fileURLToPath(new URL("../../packages/design-tokens/src/index.ts", import.meta.url)),
  "@edu/ui": fileURLToPath(new URL("../../packages/ui/src/index.ts", import.meta.url))
};
export default defineConfig({ plugins: [vue()], resolve: { alias: workspaceAlias }, server: { port: 5174 } });
