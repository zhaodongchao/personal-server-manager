<script lang="ts" setup>
import type { ToolsApi } from '#/api';

import { computed, onMounted, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';
import {
  Alert,
  Button,
  Card,
  Input,
  InputNumber,
  Radio,
  Select,
  Slider,
  Space,
  Switch,
  Tabs,
  Tag,
  Upload,
  message,
} from 'ant-design-vue';

import {
  decodeQrcodeApi,
  generateQrcodeApi,
  getQrcodeOptionsApi,
} from '#/api';
import { copyText } from '../copy';

defineOptions({ name: 'ToolsQrcode' });

const TextArea = Input.TextArea;

const opts = ref<ToolsApi.QrcodeOptions>();
const loading = ref(false);
const generating = ref(false);
const decoding = ref(false);

// ---- 内容 ----
const contentType = ref('URL');
const params = ref<Record<string, any>>({});
const currentType = computed(() =>
  (opts.value?.types ?? []).find((t) => t.value === contentType.value),
);
const typeOptions = computed(() =>
  (opts.value?.types ?? []).map((t) => ({ label: t.label, value: t.value })),
);

// ---- 样式 ----
const size = ref(320);
const margin = ref(4);
const ecc = ref('M');
const fgColor = ref('#111827');
const bgColor = ref('#FFFFFF');
const gradientOn = ref(false);
const gradientColor = ref('#22D3EE');
const dotStyle = ref('SQUARE');
const format = ref('PNG');
const logoBase64 = ref('');
const logoName = ref('');
const logoScale = ref(0.2);
const logoRound = ref(true);

// ---- 结果 ----
const result = ref<ToolsApi.QrcodeGenerateResult>();

// ---- 识别 ----
const decodeImage = ref('');
const decodeName = ref('');
const decodeResult = ref<ToolsApi.QrcodeDecodeResult>();

const canGenerate = computed(() => {
  for (const f of currentType.value?.fields ?? []) {
    if (f.required && !String(params.value[f.name] ?? '').trim()) {
      return false;
    }
  }
  return true;
});

/**
 * 按服务端下发的默认值填充参数。
 *
 * 与混淆页同一个坑：watch 只在 contentType <b>变化</b>时触发，首屏初值与清单
 * 首项相同时不会触发，必填项就会是空的，所以加载完成后必须主动调一次。
 */
function applyDefaults() {
  const next: Record<string, any> = {};
  for (const f of currentType.value?.fields ?? []) {
    if (f.def !== null && f.def !== undefined) {
      next[f.name] = f.def;
    }
  }
  params.value = next;
  result.value = undefined;
}

watch(contentType, () => applyDefaults());

onMounted(async () => {
  try {
    opts.value = await getQrcodeOptionsApi();
    size.value = opts.value.defaultSize;
    margin.value = opts.value.defaultMargin;
    const first = opts.value.types?.[0]?.value;
    if (first) {
      contentType.value = first;
    }
    applyDefaults();
  } catch {
    message.error('二维码参数加载失败，请刷新页面重试');
  } finally {
    loading.value = false;
  }
});

async function onGenerate() {
  if (!canGenerate.value) {
    message.warning('请先填写必填内容');
    return;
  }
  generating.value = true;
  try {
    result.value = await generateQrcodeApi({
      bgColor: bgColor.value,
      contentType: contentType.value,
      dotStyle: dotStyle.value,
      ecc: ecc.value,
      fgColor: fgColor.value,
      format: format.value,
      gradientColor: gradientOn.value ? gradientColor.value : '',
      logoBase64: logoBase64.value || undefined,
      logoRound: logoRound.value,
      logoScale: logoBase64.value ? logoScale.value : undefined,
      margin: margin.value,
      params: params.value,
      size: size.value,
    });
  } catch {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    generating.value = false;
  }
}

/** 读取 Logo / 待识别图片为 base64（去掉 data URL 前缀，服务端已兼容，这里省流量） */
function readFile(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const raw = String(reader.result ?? '');
      resolve(raw.includes(',') ? raw.slice(raw.indexOf(',') + 1) : raw);
    };
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

/** 只是本地读取，不走任何上传：返回 LIST_IGNORE 让组件不要往远端 POST */
function beforeLogo(file: File) {
  const max = opts.value?.maxLogoBytes ?? 512 * 1024;
  if (file.size > max) {
    message.warning(`Logo 不能超过 ${Math.floor(max / 1024)} KB`);
    return Upload.LIST_IGNORE;
  }
  readFile(file).then((base64) => {
    logoBase64.value = base64;
    logoName.value = file.name;
  });
  return Upload.LIST_IGNORE;
}

function clearLogo() {
  logoBase64.value = '';
  logoName.value = '';
}

function beforeDecodeImage(file: File) {
  const max = opts.value?.maxImageBytes ?? 2 * 1024 * 1024;
  if (file.size > max) {
    message.warning(`图片不能超过 ${Math.floor(max / 1024 / 1024)} MB`);
    return Upload.LIST_IGNORE;
  }
  readFile(file).then((base64) => {
    decodeImage.value = base64;
    decodeName.value = file.name;
    decodeResult.value = undefined;
  });
  return Upload.LIST_IGNORE;
}

async function onDecode() {
  if (!decodeImage.value) {
    message.warning('请先选择一张二维码图片');
    return;
  }
  decoding.value = true;
  try {
    decodeResult.value = await decodeQrcodeApi({
      imageBase64: decodeImage.value,
    });
    if (decodeResult.value.found) {
      message.success('识别成功');
    }
  } catch {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    decoding.value = false;
  }
}

function download() {
  if (!result.value) {
    message.warning('还没有生成二维码');
    return;
  }
  const a = document.createElement('a');
  a.href = result.value.dataUrl;
  a.download = `qrcode-${result.value.realSize}.${result.value.format.toLowerCase()}`;
  a.click();
}

function fieldOptions(field: ToolsApi.Field) {
  return (field.options ?? []).map((o) => ({ label: o.label, value: o.value }));
}
</script>

<template>
  <Page
    description="文本 / 网址 / WiFi / 名片 / 邮件 / 短信 / 定位 / 日历 / 收款 / 小程序路径 —— 尺寸、容错等级、配色、码点样式与中心 Logo 均可控，并可反向识别图片中的二维码。全部在服务端离线绘制，不落盘、不外传"
    title="二维码工具"
  >
    <Alert
      class="mb-3"
      show-icon
      type="info"
    >
      <template #message>
        关于「微信二维码 / 小程序码」：微信官方的小程序码（菊花朵码）与公众号带场景值二维码，
        只能由微信服务端 API（appid + secret 换 access_token 后调用 wxacode.get / qrcode/create）
        生成，任何第三方都无法离线算出。本工具的「微信小程序」类型产出的是
        <b>内容为小程序路径或链接的通用二维码</b>，扫码后仍需微信自行跳转。
      </template>
    </Alert>

    <Tabs>
      <Tabs.TabPane key="generate" tab="生成">
        <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <!-- 内容 -->
          <Card size="small" title="内容">
            <div class="mb-2 text-xs text-gray-500">内容类型</div>
            <Select
              v-model:value="contentType"
              :options="typeOptions"
              class="w-full"
            />
            <div v-if="currentType?.desc" class="mt-1 text-xs text-gray-400">
              {{ currentType.desc }}
            </div>

            <div
              v-for="f in currentType?.fields ?? []"
              :key="f.name"
              class="mt-3"
            >
              <div class="mb-1 text-xs text-gray-500">
                {{ f.label }}
                <span v-if="f.required" class="text-red-500">*</span>
              </div>

              <TextArea
                v-if="f.type === 'textarea'"
                v-model:value="params[f.name]"
                :placeholder="f.help || ''"
                :rows="3"
              />
              <InputNumber
                v-else-if="f.type === 'number'"
                v-model:value="params[f.name]"
                :max="f.max ?? undefined"
                :min="f.min ?? undefined"
                class="w-full"
              />
              <Select
                v-else-if="f.type === 'select'"
                v-model:value="params[f.name]"
                :options="fieldOptions(f)"
                class="w-full"
              />
              <Switch
                v-else-if="f.type === 'switch'"
                v-model:checked="params[f.name]"
              />
              <Input
                v-else-if="f.type === 'datetime'"
                v-model:value="params[f.name]"
                :placeholder="f.help || ''"
                type="datetime-local"
              />
              <Input
                v-else
                v-model:value="params[f.name]"
                :placeholder="f.help || ''"
              />
            </div>

            <Alert
              v-if="currentType?.notice"
              class="mt-3"
              :message="currentType.notice"
              show-icon
              type="warning"
            />
          </Card>

          <!-- 样式 -->
          <Card size="small" title="样式控制">
            <div class="text-xs text-gray-500">边长（像素）</div>
            <Slider
              v-model:value="size"
              :max="opts?.maxSize ?? 2048"
              :min="opts?.minSize ?? 64"
              :step="8"
            />
            <div class="-mt-1 mb-2 text-xs text-gray-400">
              请求 {{ size }}px，输出会吸附到模块的整数倍
            </div>

            <div class="text-xs text-gray-500">静默区（模块）</div>
            <InputNumber v-model:value="margin" :max="16" :min="0" class="mb-2 w-full" />

            <div class="text-xs text-gray-500">容错等级</div>
            <Select
              v-model:value="ecc"
              :options="(opts?.eccLevels ?? []).map((o) => ({ label: o.label, value: o.value }))"
              class="mb-2 w-full"
            />

            <div class="text-xs text-gray-500">码点样式</div>
            <Select
              v-model:value="dotStyle"
              :options="(opts?.dotStyles ?? []).map((o) => ({ label: o.label, value: o.value }))"
              class="mb-2 w-full"
            />

            <div class="flex items-end gap-3">
              <div>
                <div class="mb-1 text-xs text-gray-500">前景色</div>
                <input
                  v-model="fgColor"
                  class="h-8 w-12 cursor-pointer rounded border border-gray-300"
                  type="color"
                />
              </div>
              <div>
                <div class="mb-1 text-xs text-gray-500">背景色</div>
                <input
                  v-model="bgColor"
                  class="h-8 w-12 cursor-pointer rounded border border-gray-300"
                  type="color"
                />
              </div>
              <div class="flex items-center gap-1 pb-1">
                <Switch v-model:checked="gradientOn" size="small" />
                <span class="text-xs text-gray-500">渐变</span>
                <input
                  v-if="gradientOn"
                  v-model="gradientColor"
                  class="h-8 w-12 cursor-pointer rounded border border-gray-300"
                  type="color"
                />
              </div>
            </div>

            <div class="mt-3 text-xs text-gray-500">中心 Logo（可留空）</div>
            <Space class="w-full" wrap>
              <Upload
                :before-upload="beforeLogo"
                :max-count="1"
                :show-upload-list="false"
                accept="image/png,image/jpeg,image/gif"
              >
                <Button size="small">选择图片</Button>
              </Upload>
              <Button v-if="logoBase64" size="small" @click="clearLogo">
                移除
              </Button>
            </Space>
            <div v-if="logoName" class="mt-1 text-xs text-gray-400">
              {{ logoName }}
            </div>
            <template v-if="logoBase64">
              <div class="mt-2 text-xs text-gray-500">
                Logo 占比 {{ Math.round(logoScale * 100) }}%（上限
                {{ Math.round((opts?.maxLogoScale ?? 0.25) * 100) }}%，超过会扫不出来）
              </div>
              <Slider
                v-model:value="logoScale"
                :max="opts?.maxLogoScale ?? 0.25"
                :min="0.08"
                :step="0.01"
              />
              <div class="flex items-center gap-1">
                <Switch v-model:checked="logoRound" size="small" />
                <span class="text-xs text-gray-500">圆形 Logo（自动留白边）</span>
              </div>
              <div class="mt-1 text-xs text-orange-500">
                嵌 Logo 建议把容错等级调到 H
              </div>
            </template>

            <div class="mt-3 text-xs text-gray-500">输出格式</div>
            <Radio.Group
              v-model:value="format"
              :options="(opts?.formats ?? []).map((o) => ({ label: o.label, value: o.value }))"
            />

            <Button
              :disabled="!canGenerate"
              :loading="generating"
              class="mt-4 w-full"
              type="primary"
              @click="onGenerate"
            >
              生成二维码
            </Button>
          </Card>

          <!-- 结果 -->
          <Card size="small" title="结果">
            <div v-if="!result" class="py-16 text-center text-xs text-gray-400">
              填写内容后点击「生成二维码」
            </div>
            <template v-else>
              <div class="flex justify-center rounded bg-gray-50 p-3">
                <img
                  :src="result.dataUrl"
                  :style="{ width: `${Math.min(result.realSize, 260)}px` }"
                  alt="二维码"
                />
              </div>
              <div class="mt-2 flex flex-wrap gap-1 text-xs text-gray-400">
                <Tag>{{ result.format }}</Tag>
                <Tag>输出 {{ result.realSize }}px</Tag>
                <Tag>模块 {{ result.moduleCount }} × {{ result.scale }}px</Tag>
                <Tag>{{ Math.round(result.bytes / 1024) }} KB</Tag>
              </div>
              <div
                v-if="result.realSize !== result.requestSize"
                class="mt-1 text-xs text-gray-400"
              >
                请求的 {{ result.requestSize }}px 已吸附为
                {{ result.realSize }}px（模块整数倍，避免码点糊在一起）
              </div>
              <div class="mt-2 text-xs text-gray-500">实际编码内容</div>
              <TextArea :rows="4" :value="result.content" readonly />
              <Space class="mt-3" wrap>
                <Button size="small" type="primary" @click="download">
                  下载图片
                </Button>
                <Button size="small" @click="copyText(result.content)">
                  复制内容
                </Button>
              </Space>
            </template>
          </Card>
        </div>
      </Tabs.TabPane>

      <Tabs.TabPane key="decode" tab="识别">
        <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
          <Card size="small" title="上传二维码图片">
            <Upload
              :before-upload="beforeDecodeImage"
              :max-count="1"
              :show-upload-list="false"
              accept="image/png,image/jpeg,image/gif"
            >
              <Button>选择图片</Button>
            </Upload>
            <div v-if="decodeName" class="mt-2 text-xs text-gray-400">
              {{ decodeName }}
            </div>
            <Button
              :disabled="!decodeImage"
              :loading="decoding"
              class="mt-3"
              type="primary"
              @click="onDecode"
            >
              开始识别
            </Button>
            <div class="mt-2 text-xs text-gray-400">
              图片只在服务端内存中解码，不落盘、不上传第三方
            </div>
          </Card>

          <Card size="small" title="识别结果">
            <div v-if="!decodeResult" class="py-12 text-center text-xs text-gray-400">
              尚无可显示的结果
            </div>
            <template v-else>
              <Alert
                :description="decodeResult.found ? '' : decodeResult.reason"
                :message="decodeResult.found ? '识别成功' : '未识别到二维码'"
                :type="decodeResult.found ? 'success' : 'warning'"
                show-icon
              />
              <div class="mt-2 text-xs text-gray-500">内容</div>
              <TextArea :rows="6" :value="decodeResult.text" readonly />
              <Space class="mt-3" wrap>
                <Button
                  :disabled="!decodeResult.text"
                  size="small"
                  @click="copyText(decodeResult.text)"
                >
                  复制内容
                </Button>
                <Tag v-if="decodeResult.found">{{ decodeResult.format }}</Tag>
              </Space>
            </template>
          </Card>
        </div>
      </Tabs.TabPane>
    </Tabs>
  </Page>
</template>
