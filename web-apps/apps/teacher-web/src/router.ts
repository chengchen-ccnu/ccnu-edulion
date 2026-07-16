import { createRouter, createWebHistory } from "vue-router";
import LoginPage from "./views/LoginPage.vue";
import PlaceholderPage from "./views/PlaceholderPage.vue";
export const router = createRouter({history:createWebHistory(),routes:[
  {path:"/",redirect:"/teacher/dashboard"},
  {path:"/teacher/dashboard",component:PlaceholderPage,props:{heading:"教师工作台",note:"本轮只提供路由和布局。"}},
  {path:"/teacher/courses",component:PlaceholderPage,props:{heading:"课程管理",note:"课程编辑与发布不在 T01 + T03 范围。"}},
  {path:"/login",name:"login",component:LoginPage},
  {path:"/:pathMatch(.*)*",component:PlaceholderPage,props:{heading:"页面不存在",note:"请检查访问地址。"}}
]});
