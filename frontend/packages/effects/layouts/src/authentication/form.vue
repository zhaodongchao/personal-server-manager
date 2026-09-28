<script setup lang="ts">
defineOptions({
  name: 'AuthenticationFormView',
});

defineProps<{
  dataSide?: 'bottom' | 'left' | 'right' | 'top';
}>();
</script>

<template>
  <div
    class="relative flex-col-center bg-background px-6 py-10 lg:flex-initial lg:px-8 dark:bg-background-deep"
  >
    <slot></slot>
    <!--
      Router View with Transition.
      Note: 不使用 <KeepAlive :include="['Login']">。认证路由（登录/邮箱登录/找回密码/
      注册）切换依赖 <Transition mode="out-in">，被 keep-alive 缓存的 Login 在 SPA 路由
      切换时与新组件发生 out-in 冲突，会导致登录页点击「忘记密码 / 邮箱验证码登录 /
      创建账号」后目标页空白（必须强刷才恢复）。认证页不需要缓存，直接渲染即可。
    -->
    <RouterView v-slot="{ Component, route }">
      <Transition appear mode="out-in" name="slide-right">
        <component
          :is="Component"
          :key="route.fullPath"
          class="side-content mt-6 w-full sm:mx-auto md:max-w-md"
          :data-side="dataSide"
        />
      </Transition>
    </RouterView>

    <!-- Footer Copyright -->

    <div
      class="absolute right-0 bottom-3 left-0 flex justify-center text-center text-xs text-muted-foreground"
    >
      <slot name="copyright"> </slot>
    </div>
  </div>
</template>
