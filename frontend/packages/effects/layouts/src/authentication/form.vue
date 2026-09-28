<script setup lang="ts">
import { computed } from 'vue';

defineOptions({
  name: 'AuthenticationFormView',
});

const props = defineProps<{
  dataSide?: 'bottom' | 'left' | 'right' | 'top';
}>();

/**
 * side（left/right）布局把表单直接平铺在深色面板上，视觉上缺少承载容器，
 * 这里为侧边布局包一层毛玻璃卡片，提升登录 / 注册 / 忘记密码 / 邮箱登录页的质感；
 * center（bottom）布局由上层 authentication.vue 自带宽圆角卡片，本组件不再重复包裹。
 */
const isSideLayout = computed(
  () => props.dataSide === 'left' || props.dataSide === 'right',
);
</script>

<template>
  <div
    class="relative flex-col-center bg-background px-6 py-10 lg:flex-initial lg:px-8 dark:bg-background-deep"
  >
    <slot></slot>
    <!--
      Router View（认证页直接渲染，不使用过渡动画）。

      认证路由（登录/邮箱登录/找回密码/注册）为懒加载异步组件，切换时旧组件需先
      完成 out-in 离场、新异步组件才能进场挂载。该组合在 SPA 路由切换时会让内容区
      短暂/保持为空 —— 登录页点击「忘记密码 / 邮箱验证码登录 / 创建账号」后目标页
      空白，必须整页强刷才恢复。认证页不需要缓存，也别包 <Transition mode="out-in">，
      直接渲染 <RouterView> 即可保证每次切换内容区都可靠挂载。
    -->

    <!-- 侧边布局：毛玻璃卡片承载表单 -->
    <div
      v-if="isSideLayout"
      class="side-content w-full max-w-md rounded-2xl border border-muted/40 bg-background/70 p-6 shadow-xl shadow-primary/10 sm:mx-auto md:max-w-md sm:px-8 sm:py-8 backdrop-blur-md dark:bg-background-deep/60"
    >
      <RouterView v-slot="{ Component }">
        <component :is="Component" :data-side="dataSide" />
      </RouterView>
    </div>

    <!-- 居中布局：表单直接渲染，由 authentication.vue 提供圆角卡片容器 -->
    <RouterView v-else v-slot="{ Component }">
      <component
        :is="Component"
        class="side-content mt-6 w-full sm:mx-auto md:max-w-md"
        :data-side="dataSide"
      />
    </RouterView>

    <!-- Footer Copyright -->

    <div
      class="absolute right-0 bottom-3 left-0 flex justify-center text-center text-xs text-muted-foreground"
    >
      <slot name="copyright"> </slot>
    </div>
  </div>
</template>
