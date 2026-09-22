import { requestClient } from '#/api/request';

/**
 * 服务器配置管理 API 与类型定义。
 *
 * <p>与后端 `com.serverpanel.ops.controller.ServerConfigController`、`dto/*` 一一对应，
 * 前缀 `/api/v1`，读走 `ops:config:list`，增删改与「一键生效」走 `ops:config:apply`，
 * 「按历史一键恢复」走 `ops:config:rollback`。
 *
 * <p><b>核心语义：</b>{@link ServerConfigApi.Item.itemValue} 为空字符串 = 该项「不托管」，
 * 即本模块不向发行版配置里写任何东西；非空才会写进 drop-in 片段。因此「清空 → 生效」
 * 是一条合法且常用的操作（等价于让该项回到发行版默认值）。
 *
 * <p><b>破坏性入口有三个：</b>apply（一键生效）、restore（按历史恢复）、unmanage（停止托管）。
 * L3 类别（sshd）三者都要求键入后端下发的关键字（见 {@link ServerConfigApi.Category.applyKeyword}）。
 *
 * @author zhaodc
 * @since 2026-09-22 UTC+8
 */
export namespace ServerConfigApi {
  /** 通用分页结果（与后端 PageResult<T> 对应） */
  export interface Page<T> {
    records: T[];
    total: number;
    pageNum: number;
    pageSize: number;
  }

  /** 配置类别 + 宿主能力 */
  export interface Category {
    categoryKey: string;
    name: string;
    description?: string;
    /** 面板写入的托管文件（drop-in 片段）绝对路径 */
    managedFile?: string;
    /** 发行版主配置路径（多 provider 类别才有，帮助用户理解改动落在哪里） */
    sourceFile?: string;
    /** 仅 timesync：chrony / timesyncd */
    provider?: string;
    /** L1 / L2 / L3 */
    riskLevel?: string;
    applyHint?: string;
    /** L3 类别生效需键入的关键字（如 `APPLY sshd`）；非 L3 为 null */
    applyKeyword?: string;
    /** 宿主能力是否可用（不可用时应降级为只读） */
    available?: boolean;
    unavailableReason?: string;
    /** 托管文件当前是否存在（区分「从未生效过」与「已生效」） */
    managed?: boolean;
    itemCount?: number;
    /** 已托管（值非空）的配置项数 */
    managedItemCount?: number;
    sort?: number;
  }

  /** 配置项（托管值 + 系统真实生效值） */
  export interface Item {
    id?: string;
    categoryKey: string;
    /** 参数名。sysctl 为点分键；limits 为资源名；sshd 为指令名；timesync 为 ini 键 */
    itemKey: string;
    /** 当前托管值；空字符串 = 不托管该项 */
    itemValue: string;
    /** 系统当前真实生效值（宿主读取；读不到为 null） */
    effectiveValue?: null | string;
    defaultValue?: null | string;
    /** int / bool / enum / string / text */
    valueType?: string;
    /** valueType=enum 时的候选值 */
    options?: string[];
    recommended?: null | string;
    description?: string;
    sort?: number;
    /** 1 系统预置 0 用户新增 */
    builtin?: number;
  }

  /** 配置项写请求体 */
  export interface ItemBody {
    itemKey?: string;
    itemValue?: null | string;
    valueType?: string;
    options?: string[];
    recommended?: null | string;
    description?: string;
    sort?: number;
  }

  /** 预演结果（只读，不落盘） */
  export interface Preview {
    categoryKey: string;
    riskLevel?: string;
    needConfirm?: boolean;
    applyKeyword?: string;
    /** 将写入托管文件的完整内容 */
    content: string;
    /** 当前托管文件内容；从未托管过为 null */
    currentContent?: null | string;
    targetPath?: string;
    /** 行级 diff（`- 删除 / + 新增 / 空格 未变`） */
    diff?: string;
    managedItemCount?: number;
    changed?: boolean;
    /** 宿主机 dry-run 是否通过 */
    validateOk?: boolean;
    validateOutput?: string;
    /** 服务端规则错误（非空即不允许生效） */
    ruleErrors?: string[];
    /** 提示性告警（不阻断，如「仅对新会话生效」） */
    warnings?: string[];
  }

  /** 生效 / 恢复 / 停止托管的结果 */
  export interface ApplyResult {
    categoryKey: string;
    /** APPLY / RESTORE / UNMANAGE */
    op?: string;
    applied?: boolean;
    /** 失败时是否已自动回滚 */
    rolledBack?: boolean;
    message?: string;
    changeId?: string;
    backupPath?: string;
    validateOutput?: string;
    applyOutput?: string;
    diff?: string;
    durationMs?: number;
    stages?: Record<string, unknown>;
    effective?: Record<string, string>;
  }

  /** 变更历史 */
  export interface Change {
    id: string;
    categoryKey: string;
    categoryName?: string;
    /** APPLY / RESTORE / UNMANAGE */
    op: string;
    /** SUCCESS / FAILED / ROLLED_BACK */
    result: string;
    errorMsg?: string;
    operator?: string;
    operatorIp?: string;
    durationMs?: number;
    backupPath?: string;
    createdAt?: string;
    /** 是否包含下方详情字段 */
    detail?: boolean;

    beforeContent?: null | string;
    afterContent?: null | string;
    diff?: null | string;
    validateOutput?: null | string;
    applyOutput?: null | string;
    stagesText?: null | string;
    beforeItems?: Item[];
    afterItems?: Item[];
  }

  /** 变更历史查询参数 */
  export interface ChangePageParams {
    categoryKey?: string;
    pageNum?: number;
    pageSize?: number;
  }
}

/** 类别列表（含宿主能力：可用性 / 托管文件 / 风险级） */
export async function getServerConfigCategoriesApi() {
  return requestClient.get<ServerConfigApi.Category[]>(
    '/ops/config/categories',
  );
}

/** 主动重探宿主能力（装好宿主代理后无需重启面板） */
export async function detectServerConfigApi() {
  return requestClient.get<ServerConfigApi.Category[]>('/ops/config/detect');
}

/** 某类别的配置项（托管值 + 当前生效值 + 推荐值） */
export async function getServerConfigItemsApi(categoryKey: string) {
  return requestClient.get<ServerConfigApi.Item[]>(
    `/ops/config/category/${categoryKey}/items`,
  );
}

/** 新增配置项 */
export async function createServerConfigItemApi(
  categoryKey: string,
  body: ServerConfigApi.ItemBody,
) {
  return requestClient.post<ServerConfigApi.Item>(
    `/ops/config/category/${categoryKey}/item`,
    body,
  );
}

/** 修改配置项（含「清空托管值」） */
export async function updateServerConfigItemApi(
  categoryKey: string,
  itemKey: string,
  body: ServerConfigApi.ItemBody,
) {
  return requestClient.put<ServerConfigApi.Item>(
    `/ops/config/category/${categoryKey}/item/${encodeURIComponent(itemKey)}`,
    body,
  );
}

/** 删除配置项（仅删除面板里的录入，不会自动改系统） */
export async function deleteServerConfigItemApi(
  categoryKey: string,
  itemKey: string,
) {
  return requestClient.delete<void>(
    `/ops/config/category/${categoryKey}/item/${encodeURIComponent(itemKey)}`,
  );
}

/**
 * 预演：渲染全文 + diff + 宿主机 dry-run 校验。
 * 只读接口，即使内容非法也不会落盘，是「一键生效」前的推荐动作。
 */
export async function previewServerConfigApi(categoryKey: string) {
  return requestClient.get<ServerConfigApi.Preview>(
    `/ops/config/category/${categoryKey}/preview`,
  );
}

/** 一键生效（9 道闸门；L3 类别需 `confirm` 键入关键字） */
export async function applyServerConfigApi(
  categoryKey: string,
  confirm?: string,
) {
  return requestClient.post<ServerConfigApi.ApplyResult>(
    `/ops/config/category/${categoryKey}/apply`,
    { confirm },
  );
}

/**
 * 停止托管：删除该类别的 drop-in 片段及其全部备份，让发行版原配置重新生效。
 * 配置项保留在面板里，不会丢录入内容。
 */
export async function unmanageServerConfigApi(
  categoryKey: string,
  confirm?: string,
) {
  return requestClient.post<ServerConfigApi.ApplyResult>(
    `/ops/config/category/${categoryKey}/unmanage`,
    { confirm },
  );
}

/** 变更历史分页（`categoryKey` 传 `all` 或留空 = 跨类别总览） */
export async function getServerConfigChangePageApi(
  params: ServerConfigApi.ChangePageParams,
) {
  const key = params.categoryKey || 'all';
  return requestClient.get<ServerConfigApi.Page<ServerConfigApi.Change>>(
    `/ops/config/category/${key}/change/page`,
    {
      params: {
        pageNum: params.pageNum ?? 1,
        pageSize: params.pageSize ?? 20,
      },
    },
  );
}

/** 单条变更详情（前后全文 + diff + 校验/生效输出 + 配置项快照） */
export async function getServerConfigChangeDetailApi(id: string) {
  return requestClient.get<ServerConfigApi.Change>(`/ops/config/change/${id}`);
}

/**
 * 按历史一键恢复。
 *
 * @param target `before`（回到该次变更之前，默认）/ `after`（回到该次变更之后）
 * @param confirm L3 类别需键入的关键字
 */
export async function restoreServerConfigApi(
  id: string,
  target?: 'after' | 'before',
  confirm?: string,
) {
  return requestClient.post<ServerConfigApi.ApplyResult>(
    `/ops/config/change/${id}/restore`,
    { target, confirm },
  );
}
