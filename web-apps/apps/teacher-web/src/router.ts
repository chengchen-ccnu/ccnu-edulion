import { createRouter, createWebHistory } from "vue-router";
import { installAuthGuard } from "@edu/auth";
import LoginPage from "./views/LoginPage.vue";
import PlaceholderPage from "./views/PlaceholderPage.vue";
import { authStore } from "./auth";
export const router = createRouter({history:createWebHistory(),routes:[
  {path:"/",redirect:"/teacher/dashboard"},
  {path:"/teacher/dashboard",component:PlaceholderPage,meta:{requiresAuth:true},props:{heading:"教师工作台",note:"已通过 TEACHER 身份守卫。"}},
  {path:"/teacher/courses",component:PlaceholderPage,meta:{requiresAuth:true},props:{heading:"课程管理",note:"已通过 TEACHER 身份守卫；课程业务留给 T05。"}},
  {path:"/login",name:"login",component:LoginPage},
  {path:"/forbidden",name:"forbidden",component:PlaceholderPage,props:{heading:"无权访问",note:"当前账号不具备 TEACHER 角色。"}},
  {path:"/:pathMatch(.*)*",component:PlaceholderPage,props:{heading:"页面不存在",note:"请检查访问地址。"}}
]});
installAuthGuard(router, authStore);
