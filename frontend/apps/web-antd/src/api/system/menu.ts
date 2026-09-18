import { requestClient } from '#/api/request';

export namespace MenuApi {
  export interface MenuVO {
    id: string;
    parentId: string;
    menuName: string;
    menuType: 'C' | 'F' | 'M';
    routePath?: string;
    component?: string;
    perms?: string;
    icon?: string;
    sort: number;
    visible: number;
    status: number;
    children?: MenuVO[];
  }

  export interface MenuBody {
    parentId: string;
    menuName: string;
    menuType: 'C' | 'F' | 'M';
    routePath?: string;
    component?: string;
    perms?: string;
    icon?: string;
    sort?: number;
    visible?: number;
    status?: number;
  }
}

/** 菜单树 */
export async function getMenuTreeApi() {
  return requestClient.get<MenuApi.MenuVO[]>('/system/menu/tree');
}

/** 新增菜单 */
export async function createMenuApi(data: MenuApi.MenuBody) {
  return requestClient.post('/system/menu', data);
}

/** 编辑菜单 */
export async function updateMenuApi(id: string, data: MenuApi.MenuBody) {
  return requestClient.put(`/system/menu/${id}`, data);
}

/** 删除菜单 */
export async function deleteMenuApi(id: string) {
  return requestClient.delete(`/system/menu/${id}`);
}
