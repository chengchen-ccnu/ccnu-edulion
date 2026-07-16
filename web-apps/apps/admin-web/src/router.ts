import { createRouter, createWebHistory } from "vue-router";
import LoginPage from "./views/LoginPage.vue";
import PlaceholderPage from "./views/PlaceholderPage.vue";
export const router = createRouter({history:createWebHistory(),routes:[
  {path:"/",redirect:"/admin/dashboard"},
  {path:"/admin/dashboard",component:PlaceholderPage,props:{heading:"运营概览",note:"本轮只提供路由和布局。"}},
  {path:"/admin/reviews",component:PlaceholderPage,props:{heading:"审核工作台",note:"审核业务将在后续运营任务实现。"}},
  {path:"/login",name:"login",component:LoginPage},
  {path:"/:pathMatch(.*)*",component:PlaceholderPage,props:{heading:"页面不存在",note:"请检查访问地址。"}}
]});
