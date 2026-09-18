<script lang="ts" setup>
import type { DictApi } from '#/api';

import { ref } from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { useAccess } from '@vben/access';

import {
  Button,
  Card,
  message,
  Modal,
  Table,
  Tag,
} from 'ant-design-vue';

import { useVbenForm } from '#/adapter/form';
import {
  createDictDataApi,
  createDictTypeApi,
  deleteDictDataApi,
  deleteDictTypeApi,
  getDictDataPageApi,
  getDictTypePageApi,
  updateDictDataApi,
  updateDictTypeApi,
} from '#/api';

defineOptions({ name: 'SystemDict' });

const { hasAccessByCodes } = useAccess();

/** ===== 字典类型 ===== */
const selectedType = ref<null | DictApi.DictType>(null);
const typeLoading = ref(false);
const typeList = ref<DictApi.DictType[]>([]);

async function loadTypes() {
  typeLoading.value = true;
  try {
    const res = await getDictTypePageApi({ pageNum: 1, pageSize: 100 });
    typeList.value = res.records ?? [];
    // 当前选中类型被删掉后回退到第一个
    if (
      selectedType.value &&
      !typeList.value.some((t) => t.id === selectedType.value?.id)
    ) {
      selectedType.value = typeList.value[0] ?? null;
    }
  } finally {
    typeLoading.value = false;
  }
}

const typeEditing = ref(false);
const [TypeForm, typeFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: [
    {
      component: 'Input',
      fieldName: 'dictName',
      label: '字典名称',
      rules: 'required',
    },
    {
      component: 'Input',
      componentProps: { placeholder: '如 sys_normal_disable' },
      fieldName: 'dictType',
      label: '字典类型',
      rules: 'required',
    },
    {
      component: 'Textarea',
      componentProps: { rows: 2 },
      fieldName: 'remark',
      label: '备注',
    },
  ],
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [TypeModal, typeModalApi] = useVbenModal({
  async onConfirm() {
    const { valid } = await typeFormApi.validate();
    if (!valid) return;
    const values = await typeFormApi.getValues();
    const body: DictApi.DictType = {
      dictName: values.dictName,
      dictType: values.dictType,
      id: selectedType.value?.id,
      remark: values.remark,
      status: 1,
    };
    typeModalApi.lock();
    try {
      if (typeEditing.value && selectedType.value) {
        await updateDictTypeApi(body);
        message.success('修改成功');
      } else {
        await createDictTypeApi(body);
        message.success('新增成功');
      }
      typeModalApi.close();
      await loadTypes();
    } finally {
      typeModalApi.unlock();
    }
  },
});

function openTypeCreate() {
  typeEditing.value = false;
  typeFormApi.resetForm();
  typeModalApi.setData({ title: '新增字典类型' });
  typeModalApi.open();
}

function openTypeEdit(record: DictApi.DictType) {
  typeEditing.value = true;
  selectedType.value = record;
  typeFormApi.resetForm();
  typeFormApi.setValues({
    dictName: record.dictName,
    dictType: record.dictType,
    remark: record.remark,
  });
  typeModalApi.setData({ title: '编辑字典类型' });
  typeModalApi.open();
}

function confirmTypeDelete(record: DictApi.DictType) {
  Modal.confirm({
    content: `确定删除字典类型「${record.dictName}」？其字典数据将一并删除。`,
    onOk: async () => {
      await deleteDictTypeApi(record.id);
      message.success('删除成功');
      await loadTypes();
    },
    title: '删除确认',
  });
}

/** ===== 字典数据 ===== */
const dataEditing = ref(false);
const dataEditingId = ref<null | string>(null);

const [DataForm, dataFormApi] = useVbenForm({
  commonConfig: { componentProps: { class: 'w-full' } },
  schema: [
    {
      component: 'Input',
      componentProps: { disabled: true },
      fieldName: 'dictType',
      label: '所属类型',
    },
    {
      component: 'Input',
      fieldName: 'dictLabel',
      label: '标签',
      rules: 'required',
    },
    {
      component: 'Input',
      fieldName: 'dictValue',
      label: '键值',
      rules: 'required',
    },
    {
      component: 'InputNumber',
      componentProps: { min: 0, style: 'width: 100%' },
      defaultValue: 0,
      fieldName: 'sort',
      label: '排序',
    },
    {
      component: 'RadioGroup',
      componentProps: {
        options: [
          { label: '是', value: 1 },
          { label: '否', value: 0 },
        ],
      },
      defaultValue: 0,
      fieldName: 'isDefault',
      label: '默认',
    },
    {
      component: 'RadioGroup',
      componentProps: {
        options: [
          { label: '启用', value: 1 },
          { label: '停用', value: 0 },
        ],
      },
      defaultValue: 1,
      fieldName: 'status',
      label: '状态',
    },
    {
      component: 'Textarea',
      componentProps: { rows: 2 },
      fieldName: 'remark',
      label: '备注',
    },
  ],
  showDefaultActions: false,
  wrapperClass: 'grid-cols-1',
});

const [DataModal, dataModalApi] = useVbenModal({
  async onConfirm() {
    if (!selectedType.value) return;
    const { valid } = await dataFormApi.validate();
    if (!valid) return;
    const values = await dataFormApi.getValues();
    const body: DictApi.DictData = {
      dictLabel: values.dictLabel,
      dictType: selectedType.value.dictType,
      dictValue: values.dictValue,
      id: dataEditingId.value ?? undefined,
      isDefault: values.isDefault,
      remark: values.remark,
      sort: values.sort ?? 0,
      status: values.status,
    };
    dataModalApi.lock();
    try {
      if (dataEditing.value && dataEditingId.value) {
        await updateDictDataApi(body);
        message.success('修改成功');
      } else {
        await createDictDataApi(body);
        message.success('新增成功');
      }
      dataModalApi.close();
      await loadData();
    } finally {
      dataModalApi.unlock();
    }
  },
});

const dataLoading = ref(false);
const dataList = ref<DictApi.DictData[]>([]);
const dataPagination = ref({
  current: 1,
  pageSize: 20,
  total: 0,
});

async function loadData() {
  if (!selectedType.value) {
    dataList.value = [];
    return;
  }
  dataLoading.value = true;
  try {
    const res = await getDictDataPageApi({
      dictType: selectedType.value.dictType,
      pageNum: dataPagination.value.current,
      pageSize: dataPagination.value.pageSize,
    });
    dataList.value = res.records ?? [];
    dataPagination.value.total = res.total ?? 0;
  } finally {
    dataLoading.value = false;
  }
}

function onSelectType(record: DictApi.DictType) {
  selectedType.value = record;
  dataPagination.value.current = 1;
  loadData();
}

function openDataCreate() {
  if (!selectedType.value) {
    message.warning('请先选择左侧字典类型');
    return;
  }
  dataEditing.value = false;
  dataEditingId.value = null;
  dataFormApi.resetForm();
  dataFormApi.setValues({
    dictType: `${selectedType.value.dictName} (${selectedType.value.dictType})`,
    isDefault: 0,
    sort: 0,
    status: 1,
  });
  dataModalApi.setData({ title: '新增字典数据' });
  dataModalApi.open();
}

function openDataEdit(record: DictApi.DictData) {
  dataEditing.value = true;
  dataEditingId.value = record.id;
  dataFormApi.resetForm();
  dataFormApi.setValues({
    dictType: `${selectedType.value?.dictName ?? ''} (${record.dictType})`,
    dictLabel: record.dictLabel,
    dictValue: record.dictValue,
    isDefault: record.isDefault,
    remark: record.remark,
    sort: record.sort,
    status: record.status,
  });
  dataModalApi.setData({ title: '编辑字典数据' });
  dataModalApi.open();
}

function confirmDataDelete(record: DictApi.DictData) {
  Modal.confirm({
    content: `确定删除字典数据「${record.dictLabel}」？`,
    onOk: async () => {
      await deleteDictDataApi(record.id);
      message.success('删除成功');
      await loadData();
    },
    title: '删除确认',
  });
}

loadTypes();
</script>

<template>
  <Page>
    <div class="flex h-full gap-3">
      <!-- 左：字典类型 -->
      <Card class="w-80 shrink-0" title="字典类型">
        <template #extra>
          <Button
            v-if="hasAccessByCodes(['system:dict:add'])"
            size="small"
            type="primary"
            @click="openTypeCreate"
          >
            新增
          </Button>
        </template>
        <Table
          :custom-row="
            (record: any) => ({
              class:
                record.id === selectedType?.id
                  ? 'ant-table-row-selected'
                  : '',
              onClick: () => onSelectType(record),
            })
          "
          :data-source="typeList"
          :loading="typeLoading"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <Table.Column data-index="dictName" title="名称" />
          <Table.Column data-index="dictType" title="类型" />
          <Table.Column :width="110" title="操作">
            <template #default="{ record }">
              <div class="flex gap-1">
                <Button
                  v-if="hasAccessByCodes(['system:dict:edit'])"
                  size="small"
                  type="link"
                  @click.stop="openTypeEdit(record)"
                >
                  编辑
                </Button>
                <Button
                  v-if="hasAccessByCodes(['system:dict:delete'])"
                  danger
                  size="small"
                  type="link"
                  @click.stop="confirmTypeDelete(record)"
                >
                  删除
                </Button>
              </div>
            </template>
          </Table.Column>
        </Table>
      </Card>

      <!-- 右：字典数据 -->
      <Card class="min-w-0 flex-1">
        <template #title>
          字典数据
          <Tag v-if="selectedType" class="ml-2" color="processing">
            {{ selectedType.dictType }}
          </Tag>
        </template>
        <template #extra>
          <Button
            v-if="hasAccessByCodes(['system:dict:add'])"
            size="small"
            type="primary"
            @click="openDataCreate"
          >
            新增
          </Button>
        </template>
        <Table
          :data-source="dataList"
          :loading="dataLoading"
          :pagination="{
            current: dataPagination.current,
            pageSize: dataPagination.pageSize,
            showSizeChanger: false,
            total: dataPagination.total,
            onChange: (page: number) => {
              dataPagination.current = page;
              loadData();
            },
          }"
          row-key="id"
          size="small"
        >
          <Table.Column title="标签">
            <template #default="{ record }">
              <Tag v-if="record.isDefault === 1" color="blue">
                {{ record.dictLabel }}（默认）
              </Tag>
              <template v-else>{{ record.dictLabel }}</template>
            </template>
          </Table.Column>
          <Table.Column data-index="dictValue" title="键值" />
          <Table.Column data-index="sort" title="排序" :width="70" />
          <Table.Column title="状态" :width="80">
            <template #default="{ record }">
              <Tag :color="record.status === 1 ? 'success' : 'error'">
                {{ record.status === 1 ? '启用' : '停用' }}
              </Tag>
            </template>
          </Table.Column>
          <Table.Column data-index="remark" title="备注" />
          <Table.Column :width="140" fixed="right" title="操作">
            <template #default="{ record }">
              <div class="flex gap-1">
                <Button
                  v-if="hasAccessByCodes(['system:dict:edit'])"
                  size="small"
                  type="link"
                  @click="openDataEdit(record)"
                >
                  编辑
                </Button>
                <Button
                  v-if="hasAccessByCodes(['system:dict:delete'])"
                  danger
                  size="small"
                  type="link"
                  @click="confirmDataDelete(record)"
                >
                  删除
                </Button>
              </div>
            </template>
          </Table.Column>
        </Table>
      </Card>
    </div>

    <TypeModal class="w-[480px]" title="字典类型">
      <TypeForm />
    </TypeModal>
    <DataModal class="w-[480px]" title="字典数据">
      <DataForm />
    </DataModal>
  </Page>
</template>
