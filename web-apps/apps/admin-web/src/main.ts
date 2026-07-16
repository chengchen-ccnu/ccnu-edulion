import { createApp } from "vue";
import "@edu/design-tokens/tokens.css";
import App from "./App.vue";
import { router } from "./router";
import "./styles.css";
createApp(App).use(router).mount("#app");
