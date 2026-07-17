import { createRouter, createWebHistory } from "vue-router";
import { installAuthGuard } from "@edu/auth";
import LoginPage from "./views/LoginPage.vue";
import PlaceholderPage from "./views/PlaceholderPage.vue";
import { authStore } from "./auth";
export const router = createRouter({history:createWebHistory(),routes:[
  {path:"/",redirect:"/admin/dashboard"},
  {path:"/admin/dashboard",component:PlaceholderPage,meta:{requiresAuth:true},props:{heading:"运营概览",note:"已通过 ADMIN 身份守卫。"}},
  {path:"/admin/reviews",component:PlaceholderPage,meta:{requiresAuth:true},props:{heading:"审核工作台",note:"已通过 ADMIN 身份守卫；审核业务留给后续任务。"}},
  {path:"/login",name:"login",component:LoginPage},
  {path:"/forbidden",name:"forbidden",component:PlaceholderPage,props:{heading:"无权访问",note:"当前账号不具备 ADMIN 角色。"}},
  {path:"/:pathMatch(.*)*",component:PlaceholderPage,props:{heading:"页面不存在",note:"请检查访问地址。"}}
]});
installAuthGuard(router, authStore);
