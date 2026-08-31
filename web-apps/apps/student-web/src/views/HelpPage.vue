<script setup lang="ts">
/** 静态帮助页只解释真实已交付的账号操作，不展示没有后端契约的反馈或申诉按钮。 */
const items = [
  ["如何登录？", "请使用已验证邮箱或中国大陆手机号。密码登录和验证码登录都不会使用昵称或内部账号。"],
  ["收不到验证码怎么办？", "检查身份输入和垃圾邮件文件夹，等待发送冷却后有限重试。验证码过期或消费后，请重新完成算式验证码。"],
  ["如何修改密码？", "登录后打开账号菜单中的个人中心，再进入账号安全页。修改密码会让所有设备重新登录。"],
  ["手机入口为什么不可用？", "手机注册和手机验证码登录由服务端渠道能力控制。当前环境未配置短信供应商时，页面会保留邮箱入口并隐藏手机发送操作。"],
  ["如何申请教师能力？", "登录普通账号后进入个人中心的教师申请页面，上传资质并提交说明。审核决定和教学能力授予由后续管理流程负责。"]
] as const;
</script>

<template>
  <section class="content-page help-page" aria-labelledby="help-title"><span class="badge">帮助中心</span><h1 id="help-title">账号与安全帮助</h1><p class="page-lead">这里提供已接通功能的使用说明。暂不提供无契约的反馈提交、人工申诉或账号注销入口。</p><dl class="help-list"><div v-for="item in items" :key="item[0]"><dt>{{ item[0] }}</dt><dd>{{ item[1] }}</dd></div></dl><nav class="account-links"><RouterLink to="/login">去登录</RouterLink><RouterLink to="/terms">用户协议</RouterLink><RouterLink to="/privacy">隐私政策</RouterLink></nav></section>
</template>
