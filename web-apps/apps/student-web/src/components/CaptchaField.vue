<script setup lang="ts">
/** 算式验证码展示控件；图片只由服务端返回，答案由用户输入且从不进入 URL、日志或持久化状态。 */
import type { ArithmeticCaptcha } from "@edu/api-client";

defineProps<{ captcha: ArithmeticCaptcha | null; answer: string; disabled?: boolean }>();
const emit = defineEmits<{ "update:answer": [value: string]; refresh: [] }>();
</script>

<template>
  <div class="captcha-field">
    <label>
      <span>算式验证码</span>
      <div class="captcha-field__row">
        <img v-if="captcha" :src="`data:${captcha.imageMimeType};base64,${captcha.imageBase64}`"
          alt="算式验证码图片，看不清可点击换一张" class="captcha-field__image" />
        <span v-else class="captcha-field__image captcha-field__image--loading" role="img"
          aria-label="算式验证码加载中">加载中</span>
        <button type="button" class="button button--quiet captcha-field__refresh" :disabled="disabled || !captcha"
          @click="emit('refresh')">换一张</button>
      </div>
      <input :value="answer" inputmode="numeric" autocomplete="off" maxlength="4"
        placeholder="请输入图片中的计算结果" :disabled="disabled || !captcha"
        @input="emit('update:answer', ($event.target as HTMLInputElement).value)" />
    </label>
    <small>验证码约 2 分钟有效；答案只用于本次安全操作。</small>
  </div>
</template>
