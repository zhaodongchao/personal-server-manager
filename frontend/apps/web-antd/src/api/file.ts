import { useAccessStore } from '@vben/stores';

import { baseRequestClient, requestClient } from '#/api/request';

export namespace FileApi {
  /** 文件/目录条目 */
  export interface FileEntry {
    name: string;
    path: string;
    dir: boolean;
    size: number;
    lastModified: number;
    perms?: string;
    text: boolean;
  }

  /** 回收站记录 */
  export interface RecycleItem {
    id: string;
    originPath: string;
    trashPath: string;
    fileName: string;
    isDir: number;
    size: number;
    operator: string;
    createdAt: string;
    expireAt: string;
  }
}

function authHeaders() {
  const accessStore = useAccessStore();
  const token = accessStore.accessToken;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** 白名单根目录 */
export async function getFileRootsApi() {
  return requestClient.get<FileApi.FileEntry[]>('/file/roots');
}

/** 目录内容 */
export async function getFileListApi(path: string) {
  return requestClient.get<FileApi.FileEntry[]>('/file/list', {
    params: { path },
  });
}

/** 新建目录 */
export async function createDirApi(path: string) {
  return requestClient.post('/file/mkdir', { path });
}

/** 重命名 */
export async function renameFileApi(path: string, newName: string) {
  return requestClient.post('/file/rename', { newName, path });
}

/** 移动 */
export async function moveFileApi(sourcePath: string, targetDir: string) {
  return requestClient.post('/file/move', { sourcePath, targetDir });
}

/** 上传（多文件） */
export async function uploadFileApi(dir: string, files: File[]) {
  const form = new FormData();
  form.append('dir', dir);
  files.forEach((file) => form.append('files', file));
  return requestClient.post('/file/upload', form);
}

/** 下载（blob 流） */
export async function downloadFileApi(path: string) {
  const resp = await baseRequestClient.get('/file/download', {
    headers: authHeaders(),
    params: { path },
    responseType: 'blob',
  });
  const disposition: string = resp.headers?.['content-disposition'] ?? '';
  const match = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  const fallback = path.split('/').pop() || 'download';
  const name = match ? decodeURIComponent(match[1] ?? '') : fallback;
  const url = URL.createObjectURL(resp.data as Blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  URL.revokeObjectURL(url);
}

/** 读取文本内容 */
export async function getFileContentApi(path: string) {
  return requestClient.get<string>('/file/content', { params: { path } });
}

/** 保存文本内容 */
export async function updateFileContentApi(path: string, content: string) {
  return requestClient.put('/file/content', { content, path });
}

/** 修改权限 */
export async function setFilePermApi(path: string, mode: string) {
  return requestClient.post('/file/perm', { mode, path });
}

/** 压缩 */
export async function compressFileApi(path: string, format: 'zip' | 'tar.gz') {
  return requestClient.post('/file/compress', { format, path });
}

/** 解压 */
export async function extractFileApi(archivePath: string, targetDir: string) {
  return requestClient.post('/file/extract', { archivePath, targetDir });
}

/** 删除（入回收站） */
export async function deleteFileApi(path: string) {
  return requestClient.delete('/file', { params: { path } });
}

/** 回收站分页 */
export async function getRecyclePageApi(params: {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
}) {
  return requestClient.get('/file/recycle', { params });
}

/** 还原 */
export async function restoreRecycleApi(id: string) {
  return requestClient.post(`/file/recycle/restore/${id}`);
}

/** 彻底删除 */
export async function purgeRecycleApi(id: string) {
  return requestClient.delete(`/file/recycle/${id}`);
}

/** 清空回收站 */
export async function emptyRecycleApi() {
  return requestClient.post('/file/recycle/empty');
}
