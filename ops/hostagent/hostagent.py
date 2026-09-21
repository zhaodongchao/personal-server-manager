#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ServerPanel 宿主代理（psm-hostagent）—— 面板容器与宿主机之间的唯一执行通道。

作者: zhaodc          创建时间: 2026-09-21
协议版本: 1

背景
----
面板后端跑在容器里（基镜像 eclipse-temurin:21-jre，实为 Ubuntu 26.04），
容器内**没有** systemctl / journalctl / ufw / firewall-cmd；而「服务管理、
计划管理、防火墙管理」三个模块需要在**宿主机**上执行系统命令。本代理即为此
而生的宿主侧常驻进程。

安全设计（与 psm/design/ops-tools-refactor-design.md ADR-1 对应）
--------------------------------------------------------------
  1) 只监听 AF_UNIX 域套接字（默认 /run/psm-hostagent/agent.sock），
     **永不监听任何 TCP 端口**，对外零网络暴露面；
  2) 共享密钥鉴权（/etc/psm-hostagent/secret，0400），hmac.compare_digest 恒定时比较；
  3) **操作白名单**：只执行 OPS 枚举内的 op，每个 op 自带参数校验（正则 + 语义），
     即使面板被攻破也无法借本代理执行任意命令；
  4) 所有外部命令一律以 argv 数组调用 subprocess（shell=False），绝不拼接 shell 字符串；
  5) 单请求超时 + 请求体大小上限 + 输出截断，防止资源耗尽；
  6) 每次调用写结构化日志（/var/lib/psm-hostagent/logs/agent.log）。

CLI
---
  hostagent.py serve              启动服务（由 systemd 调用）
  hostagent.py probe              打印本机能力探测结果（安装自检用）
  hostagent.py rollback <id>      执行一次「待回滚快照」（由 systemd-run 定时器调用）
"""

from __future__ import annotations

import hmac
import ipaddress
import json
import os
import platform
import re
import shutil
import socketserver
import subprocess
import sys
import time
from datetime import datetime, timezone

VERSION = '1.0.0'
PROTOCOL_VERSION = 1

SECRET_FILE = os.environ.get('PSM_HOSTAGENT_SECRET_FILE', '/etc/psm-hostagent/secret')
RUN_DIR = os.environ.get('PSM_HOSTAGENT_RUN_DIR', '/run/psm-hostagent')
SOCKET_PATH = os.environ.get('PSM_HOSTAGENT_SOCKET', os.path.join(RUN_DIR, 'agent.sock'))
SOCKET_GROUP = os.environ.get('PSM_HOSTAGENT_GROUP', 'zhaodc')
STATE_DIR = os.environ.get('PSM_HOSTAGENT_STATE_DIR', '/var/lib/psm-hostagent')
PENDING_DIR = os.path.join(STATE_DIR, 'pending')
LOG_DIR = os.path.join(STATE_DIR, 'logs')
LOG_FILE = os.path.join(LOG_DIR, 'agent.log')
SELF_PATH = os.path.join('/usr/local/lib/psm-hostagent', 'hostagent.py')

MAX_REQUEST_BYTES = 256 * 1024
MAX_OUTPUT_BYTES = 2 * 1024 * 1024
MAX_LINES = 5000
DEFAULT_TIMEOUT = 30
MAX_TIMEOUT = 900
MAX_LOG_BYTES = 5 * 1024 * 1024

SAFE_ENV = {
    'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
    'LC_ALL': 'C',
    'LANG': 'C',
    'HOME': '/root',
    'TZ': 'Asia/Shanghai',
}

TOOL_CANDIDATES = {
    'systemctl': ['/usr/bin/systemctl', '/bin/systemctl', 'systemctl'],
    'journalctl': ['/usr/bin/journalctl', '/bin/journalctl', 'journalctl'],
    'ufw': ['/usr/sbin/ufw', '/sbin/ufw', 'ufw'],
    'firewall-cmd': ['/usr/bin/firewall-cmd', '/usr/sbin/firewall-cmd', 'firewall-cmd'],
    'iptables': ['/usr/sbin/iptables', '/sbin/iptables', 'iptables'],
    'nft': ['/usr/sbin/nft', 'nft'],
    'ss': ['/usr/bin/ss', '/sbin/ss', 'ss'],
    'sshd': ['/usr/sbin/sshd', '/usr/bin/sshd', 'sshd'],
    'systemd-run': ['/usr/bin/systemd-run', '/bin/systemd-run', 'systemd-run'],
    'fail2ban-client': ['/usr/bin/fail2ban-client', 'fail2ban-client'],
    'docker': ['/usr/bin/docker', '/usr/local/bin/docker', 'docker'],
    'nginx': ['/usr/sbin/nginx', 'nginx'],
    'python3': ['/usr/bin/python3', 'python3'],
}

# systemd 会把 unit 名里的 `-` 转义成 `\x2d`、`/` 转义成 `-`，所以**合法 unit 名可以
# 含反斜杠**（本机实例：systemd-fsck@dev-debian\x2dvg-docker_data.service），且根挂载单元
# `-.mount` 以 `-` 开头。旧正则拒绝这两类名字，而面板按 80 个 unit 分片查询，**一个名字非法
# 就整片失败**，表现为「列表里部分单元的内存/运行时长/重启次数全为 null」。
# 仍然严格排除空格、引号、`;`、`|`、`$`、`*`、`?`、`[`、`]`、`/` —— 注入与路径穿越照样挡死。
UNIT_RE = re.compile(r'^[A-Za-z0-9_@\\-][A-Za-z0-9_.@:\\-]{0,254}$')
SERVICE_ACTIONS = {
    'start', 'stop', 'restart', 'reload', 'try-restart',
    'enable', 'disable', 'mask', 'unmask', 'reset-failed', 'kill',
}
SIGNALS = {'SIGHUP', 'SIGINT', 'SIGTERM', 'SIGKILL', 'SIGUSR1', 'SIGUSR2', 'SIGQUIT'}

FW_ACTIONS = {'allow', 'deny', 'reject', 'limit'}
FW_PROTOCOLS = {'tcp', 'udp', 'any'}
FW_TARGET_KINDS = {'port', 'range', 'multi', 'any'}
FW_POLICIES = {'allow', 'deny', 'reject'}
WATCHDOG_ID_RE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9-]{2,40}$')
WATCHDOG_OPS = {'firewall.addRule', 'firewall.deleteRule', 'firewall.deleteByNo',
                'firewall.setEnabled', 'firewall.setDefault', 'firewall.reload'}


class OpError(Exception):
    """业务级错误（返回给面板，不打印堆栈）。"""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


# --------------------------------------------------------------------------- #
# 基础工具
# --------------------------------------------------------------------------- #

def _now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec='seconds')


def tool(name: str):
    """解析外部命令的可用路径；不存在返回 None。"""
    for cand in TOOL_CANDIDATES.get(name, [name]):
        if os.path.isabs(cand):
            if os.path.exists(cand) and os.access(cand, os.X_OK):
                return cand
        else:
            found = shutil.which(cand)
            if found:
                return found
    return None


def require_tool(name: str) -> str:
    path = tool(name)
    if not path:
        raise OpError('tool-missing', '宿主机缺少命令: %s' % name)
    return path


def run(argv, timeout: int = DEFAULT_TIMEOUT, env=None) -> dict:
    """以 argv 数组执行外部命令（shell=False），带超时与输出截断。"""
    timeout = max(1, min(int(timeout or DEFAULT_TIMEOUT), MAX_TIMEOUT))
    full_env = dict(SAFE_ENV)
    if env:
        full_env.update({str(k): str(v) for k, v in env.items()})
    started = time.time()

    def elapsed() -> int:
        return int((time.time() - started) * 1000)

    try:
        proc = subprocess.Popen(
            [str(a) for a in argv],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL,
            env=full_env, close_fds=True, cwd='/',
        )
    except FileNotFoundError:
        return {'exitCode': 127, 'stdout': '', 'stderr': '命令不存在: %s' % argv[0],
                'timedOut': False, 'truncated': False, 'durationMs': elapsed()}
    except OSError as exc:
        return {'exitCode': 126, 'stdout': '', 'stderr': '命令启动失败: %s' % exc,
                'timedOut': False, 'truncated': False, 'durationMs': elapsed()}

    timed_out = False
    try:
        raw_out, raw_err = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        proc.kill()
        try:
            raw_out, raw_err = proc.communicate(timeout=5)
        except Exception:                                   # noqa: BLE001
            raw_out, raw_err = b'', b''

    out, truncated_out = _decode(raw_out)
    err, truncated_err = _decode(raw_err)
    return {
        'exitCode': -1 if timed_out else proc.returncode,
        'stdout': out,
        'stderr': err,
        'timedOut': timed_out,
        'truncated': truncated_out or truncated_err,
        'durationMs': elapsed(),
    }


def _decode(raw: bytes):
    truncated = False
    if len(raw) > MAX_OUTPUT_BYTES:
        raw = raw[:MAX_OUTPUT_BYTES]
        truncated = True
    text = raw.decode('utf-8', errors='replace').replace('\r\n', '\n')
    return text, truncated


def ok(result=None, data=None) -> dict:
    """把 run() 结果规整为统一响应体。"""
    base = result or {'exitCode': 0, 'stdout': '', 'stderr': '', 'timedOut': False,
                      'truncated': False, 'durationMs': 0}
    body = {
        'exitCode': base.get('exitCode', 0),
        'stdout': base.get('stdout', ''),
        'stderr': base.get('stderr', ''),
        'timedOut': bool(base.get('timedOut', False)),
        'truncated': bool(base.get('truncated', False)),
        'durationMs': base.get('durationMs', 0),
    }
    if data is not None:
        body['data'] = data
    return body


def merge(result: dict, data) -> dict:
    result = dict(result)
    result['data'] = data
    return result


def valid_unit(name) -> str:
    if not isinstance(name, str) or not UNIT_RE.match(name) or '..' in name:
        raise OpError('bad-request', '非法的 unit 名: %r' % (name,))
    return name


def valid_iso(value, label: str):
    if value is None or value == '':
        return None
    if not isinstance(value, str) or not re.match(r'^[0-9A-Za-z: +\-.]{1,32}$', value):
        raise OpError('bad-request', '%s 时间格式非法' % label)
    return value


def read_os_pretty() -> str:
    try:
        with open('/etc/os-release', encoding='utf-8') as f:
            for line in f:
                if line.startswith('PRETTY_NAME='):
                    return line.split('=', 1)[1].strip().strip('"')
    except OSError:
        pass
    return platform.platform()


def log_event(payload: dict) -> None:
    """追加一行 JSON 日志；超过上限自动轮转一次。"""
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        if os.path.exists(LOG_FILE) and os.path.getsize(LOG_FILE) > MAX_LOG_BYTES:
            try:
                os.replace(LOG_FILE, LOG_FILE + '.1')
            except OSError:
                pass
        with open(LOG_FILE, 'a', encoding='utf-8') as f:
            f.write(json.dumps(payload, ensure_ascii=False) + '\n')
    except OSError:
        pass                                                 # 日志失败绝不影响业务


# --------------------------------------------------------------------------- #
# op 注册表
# --------------------------------------------------------------------------- #

OPS = {}


def op(name):
    def deco(fn):
        OPS[name] = fn
        return fn
    return deco


# ---------------------------- 主机 / 能力 ---------------------------- #

# 允许经 host.exec 执行的程序名（与后端 CommandExecutor 的内置白名单保持一致）。
# 刻意不含任何 shell 解释器：host.exec 只接受 argv 数组，即便白名单被绕过也拼不出 shell。
EXEC_WHITELIST = frozenset({
    'ps', 'kill', 'systemctl', 'journalctl', 'nginx', 'ufw', 'firewall-cmd',
    'mysql', 'mysqldump', 'mysqladmin', 'pvs', 'vgs', 'lvs', 'pvdisplay',
    'vgdisplay', 'lvdisplay', 'lsblk', 'fdisk', 'df', 'findmnt',
})


@op('host.exec')
def op_host_exec(args):
    """在宿主机上执行一条白名单命令（argv 数组，绝不经过 shell）。

    为计划任务提供「真能跑起来」的执行通道：面板容器里没有 systemctl / ufw / mysql / df
    等系统命令，在容器内执行必然失败。这里守住三条边界：
      1) 只接受 argv 数组，不接受命令字符串 —— 从根上杜绝 shell 注入；
      2) 程序名必须在白名单内，且解析为绝对路径再执行；
      3) 超时由代理侧终止进程，输出按上限截断。
    """
    argv = args.get('argv')
    if not isinstance(argv, list) or not argv:
        raise OpError('bad-request', 'argv 必须是非空数组')
    if len(argv) > 64:
        raise OpError('bad-request', 'argv 参数过多（上限 64）')
    argv = [str(a) for a in argv]
    prog = argv[0]
    if prog not in EXEC_WHITELIST:
        raise OpError('cmd-not-allowed', '命令不在白名单内: %s' % prog)
    argv[0] = require_tool(prog)
    return run(argv, timeout=args.get('timeout'))


@op('host.ping')
def op_host_ping(args):
    return ok(data={'agentVersion': VERSION, 'protocol': PROTOCOL_VERSION,
                    'now': _now_iso(), 'pid': os.getpid()})


@op('host.probe')
def op_host_probe(args):
    tools, missing = {}, []
    # 除固定的运维命令外，还要探测 host.exec 白名单里的程序：
    # 「命令白名单」接口要靠这份结果告诉前端哪些命令在宿主机上真实存在。
    # 若只探测 TOOL_CANDIDATES，df / ps / lsblk 之类会被误报成「宿主机缺失」——
    # 把「没探测」当成了「不存在」。（tool() 对未登记的名字会回落 shutil.which）
    for name in list(TOOL_CANDIDATES) + [
            n for n in sorted(EXEC_WHITELIST) if n not in TOOL_CANDIDATES]:
        path = tool(name)
        if path:
            tools[name] = path
        else:
            missing.append(name)
    sysrun = ''
    if tools.get('systemctl'):
        sysrun = run([tools['systemctl'], 'is-system-running'], timeout=10)
        sysrun = (sysrun.get('stdout') or sysrun.get('stderr') or '').strip()
    backend = 'ufw' if tools.get('ufw') else ('firewalld' if tools.get('firewall-cmd') else 'none')
    return ok(data={
        'agentVersion': VERSION,
        'protocol': PROTOCOL_VERSION,
        'os': read_os_pretty(),
        'kernel': platform.release(),
        'python': platform.python_version(),
        'socket': SOCKET_PATH,
        'tools': tools,
        'missing': missing,
        'systemRunning': sysrun or 'unknown',
        'firewallBackend': backend,
        'pid1': os.path.realpath('/proc/1/exe') if os.path.exists('/proc/1/exe') else None,
    })


@op('host.info')
def op_host_info(args):
    uptime = None
    try:
        with open('/proc/uptime', encoding='utf-8') as f:
            uptime = float(f.read().split()[0])
    except OSError:
        pass
    return ok(data={'os': read_os_pretty(), 'kernel': platform.release(),
                    'hostname': platform.node(), 'uptimeSeconds': uptime,
                    'now': _now_iso()})


@op('host.listenPorts')
def op_host_listen_ports(args):
    ss = require_tool('ss')
    return run([ss, '-lntp'], timeout=15)


@op('host.sshdPorts')
def op_host_sshd_ports(args):
    ports, source = [], None
    sshd = tool('sshd')
    if sshd:
        r = run([sshd, '-T'], timeout=15)
        if r['exitCode'] == 0:
            source = 'sshd -T'
            for line in r['stdout'].splitlines():
                if line.startswith('port '):
                    try:
                        p = int(line.split()[1])
                        if p not in ports:
                            ports.append(p)
                    except (IndexError, ValueError):
                        pass
    if not ports:
        files = ['/etc/ssh/sshd_config']
        d = '/etc/ssh/sshd_config.d'
        if os.path.isdir(d):
            files += sorted(os.path.join(d, n) for n in os.listdir(d) if n.endswith('.conf'))
        contents = []
        for path in files:
            try:
                with open(path, encoding='utf-8', errors='replace') as f:
                    contents.append({'path': path, 'content': f.read()})
            except OSError:
                continue
        source = 'config-files'
        for item in contents:
            for line in item['content'].splitlines():
                m = re.match(r'^\s*Port\s+(\d+)\s*$', line, re.IGNORECASE)
                if m:
                    p = int(m.group(1))
                    if p not in ports:
                        ports.append(p)
        if not ports:
            ports = [22]
        return ok(data={'ports': ports, 'source': source, 'files': contents})
    return ok(data={'ports': ports or [22], 'source': source, 'files': []})


# ---------------------------- 服务管理 ---------------------------- #

@op('service.listUnitFiles')
def op_service_list_unit_files(args):
    return run([require_tool('systemctl'), 'list-unit-files', '--type=service',
                '--no-legend', '--no-pager', '--plain'], timeout=30)


@op('service.listUnits')
def op_service_list_units(args):
    return run([require_tool('systemctl'), 'list-units', '--type=service', '--all',
                '--no-legend', '--no-pager', '--plain'], timeout=30)


@op('service.failed')
def op_service_failed(args):
    return run([require_tool('systemctl'), '--failed', '--no-legend', '--plain',
                '--no-pager'], timeout=30)


@op('service.show')
def op_service_show(args):
    units = args.get('units') or []
    if not isinstance(units, list) or not units:
        raise OpError('bad-request', 'units 不能为空')
    if len(units) > 400:
        raise OpError('bad-request', 'units 数量超上限（400）')
    props = args.get('properties') or [
        'MainPID', 'MemoryCurrent', 'CPUUsageNSec', 'ActiveEnterTimestamp',
        'NRestarts', 'FragmentPath', 'ActiveState', 'SubState', 'UnitFileState',
        'LoadState', 'Description', 'WantedBy', 'Requires', 'After', 'ExecStart',
    ]
    argv = [require_tool('systemctl'), 'show']
    argv += [valid_unit(u) for u in units]
    for p in props:
        if not re.match(r'^[A-Za-z][A-Za-z0-9]{1,40}$', str(p)):
            raise OpError('bad-request', '非法属性名: %r' % (p,))
        argv.append('-p')
        argv.append(str(p))
    argv += ['--no-pager']
    return run(argv, timeout=60)


@op('service.dependencies')
def op_service_dependencies(args):
    name = valid_unit(args.get('name'))
    argv = [require_tool('systemctl'), 'list-dependencies', '--plain', '--no-pager']
    if args.get('reverse'):
        argv.append('--reverse')
    argv.append(name)
    return run(argv, timeout=45)


@op('service.status')
def op_service_status(args):
    name = valid_unit(args.get('name'))
    return run([require_tool('systemctl'), 'status', name, '--no-pager', '--full'], timeout=30)


@op('service.cat')
def op_service_cat(args):
    name = valid_unit(args.get('name'))
    return run([require_tool('systemctl'), 'cat', name, '--no-pager'], timeout=30)


@op('service.isActive')
def op_service_is_active(args):
    name = valid_unit(args.get('name'))
    systemctl = require_tool('systemctl')
    active = run([systemctl, 'is-active', name], timeout=15)
    enabled = run([systemctl, 'is-enabled', name], timeout=15)
    return merge(ok(), {'isActive': (active.get('stdout') or '').strip(),
                        'isEnabled': (enabled.get('stdout') or '').strip(),
                        'isActiveCode': active.get('exitCode'),
                        'isEnabledCode': enabled.get('exitCode')})


@op('service.logs')
def op_service_logs(args):
    name = valid_unit(args.get('name'))
    lines = args.get('lines', 200)
    try:
        lines = max(1, min(int(lines), MAX_LINES))
    except (TypeError, ValueError):
        lines = 200
    fmt = str(args.get('format') or 'short-iso')
    if fmt not in ('json', 'short-iso', 'short', 'short-precise', 'cat'):
        raise OpError('bad-request', '不支持的日志格式: %r' % (fmt,))
    argv = [require_tool('journalctl'), '-u', name, '-n', str(lines),
            '--no-pager', '-o', fmt]
    if fmt != 'json':
        argv.append('--no-hostname')
    since = valid_iso(args.get('since'), 'since')
    until = valid_iso(args.get('until'), 'until')
    if since:
        argv += ['--since', since]
    if until:
        argv += ['--until', until]
    if args.get('reverse'):
        argv.append('--reverse')
    if args.get('priority'):
        pr = str(args['priority'])
        if not re.match(r'^[0-7]$', pr):
            raise OpError('bad-request', 'priority 只能是 0-7')
        argv += ['-p', pr]
    return run(argv, timeout=60)


@op('service.action')
def op_service_action(args):
    name = valid_unit(args.get('name'))
    action = args.get('action')
    if action not in SERVICE_ACTIONS:
        raise OpError('bad-request', '不支持的动作: %r' % (action,))
    options = args.get('options') or {}
    if not isinstance(options, dict):
        raise OpError('bad-request', 'options 必须是对象')
    systemctl = require_tool('systemctl')
    argv = [systemctl, action]
    if action == 'kill':
        signal = str(options.get('signal') or 'SIGTERM').upper()
        if signal not in SIGNALS:
            raise OpError('bad-request', '不支持的信号: %s' % signal)
        argv += ['-s', signal, name]
    else:
        if options.get('now') and action in ('enable', 'disable', 'mask', 'unmask'):
            argv.append('--now')
        argv.append(name)
    return run(argv, timeout=90)


@op('service.daemonReload')
def op_service_daemon_reload(args):
    return run([require_tool('systemctl'), 'daemon-reload'], timeout=60)


# ---------------------------- 防火墙 ---------------------------- #

def _fw_argv() -> str:
    return require_tool('ufw')


def _valid_port(value, label='端口') -> int:
    try:
        port = int(value)
    except (TypeError, ValueError):
        raise OpError('bad-request', '%s 必须是整数' % label)
    if not 1 <= port <= 65535:
        raise OpError('bad-request', '%s 超出范围 1-65535' % label)
    return port


def _valid_source(value) -> str:
    if value is None or value == '' or value == 'any':
        return 'any'
    text = str(value).strip()
    try:
        if '/' in text:
            net = ipaddress.ip_network(text, strict=False)
            return str(net)
        return str(ipaddress.ip_address(text))
    except ValueError:
        raise OpError('bad-request', '来源地址非法: %s' % text)


def _valid_comment(value) -> str:
    if value is None:
        return ''
    text = re.sub(r'\s+', ' ', str(value)).strip().strip("'\"")
    if not text:
        return ''
    if not text.startswith('psm:'):
        text = 'psm: ' + text
    if len(text) > 90:
        text = text[:90]
    return text


def _fw_spec(args, with_comment: bool):
    """把结构化规则编译为 ufw 的 argv 尾巴（永不拼 shell 字符串）。"""
    kind = args.get('kind') or 'port'
    if kind not in FW_TARGET_KINDS:
        raise OpError('bad-request', '不支持的目标类型: %r' % (kind,))
    action = args.get('action')
    if action not in FW_ACTIONS:
        raise OpError('bad-request', '不支持的动作: %r' % (action,))
    protocol = args.get('protocol') or 'tcp'
    if protocol not in FW_PROTOCOLS:
        raise OpError('bad-request', '协议仅支持 tcp/udp/any')
    source = _valid_source(args.get('source'))

    tail = [action]
    if kind == 'port':
        port = _valid_port(args.get('port'))
        target = '%d/%s' % (port, protocol) if protocol != 'any' else '%d' % port
        if source == 'any':
            tail.append(target)
        else:
            tail += ['from', source, 'to', 'any', 'port', str(port)]
            if protocol != 'any':
                tail += ['proto', protocol]
    elif kind == 'range':
        port = _valid_port(args.get('port'), '起始端口')
        end = _valid_port(args.get('portEnd'), '结束端口')
        if end < port:
            raise OpError('bad-request', '结束端口不能小于起始端口')
        spec = '%d:%d' % (port, end)
        target = '%s/%s' % (spec, protocol) if protocol != 'any' else spec
        if source == 'any':
            tail.append(target)
        else:
            tail += ['from', source, 'to', 'any', 'port', spec]
            if protocol != 'any':
                tail += ['proto', protocol]
    elif kind == 'multi':
        ports = args.get('ports') or []
        if not isinstance(ports, list) or not ports:
            raise OpError('bad-request', '多端口至少要填一个端口')
        if len(ports) > 15:
            raise OpError('bad-request', '多端口最多 15 个')
        nums = [_valid_port(p) for p in ports]
        spec = ','.join(str(n) for n in nums)
        if protocol == 'any':
            raise OpError('bad-request', '多端口必须指定 tcp 或 udp 协议')
        if source == 'any':
            tail.append('%s/%s' % (spec, protocol))
        else:
            tail += ['from', source, 'to', 'any', 'port', spec, 'proto', protocol]
    else:                                                    # kind == 'any'
        if source == 'any':
            raise OpError('bad-request',
                          '「任意端口 + 任意来源」等价于默认策略，请改用默认策略设置')
        tail += ['from', source]
        if protocol != 'any':
            tail += ['proto', protocol]

    if with_comment:
        comment = _valid_comment(args.get('comment'))
        if comment:
            tail += ['comment', comment]
    return tail


@op('firewall.statusRaw')
def op_firewall_status_raw(args):
    ufw = _fw_argv()
    verbose = run([ufw, 'status', 'verbose'], timeout=20)
    numbered = run([ufw, 'status', 'numbered'], timeout=20)
    return ok(verbose, data={'numbered': numbered.get('stdout', ''),
                             'numberedStderr': numbered.get('stderr', ''),
                             'numberedExitCode': numbered.get('exitCode')})


@op('firewall.raw')
def op_firewall_raw(args):
    return run([_fw_argv(), 'show', 'raw'], timeout=30)


@op('firewall.addRule')
def op_firewall_add_rule(args):
    tail = _fw_spec(args, with_comment=True)
    argv = [_fw_argv()] + tail
    return merge(run(argv, timeout=45), {'spec': tail, 'command': ' '.join(argv)})


@op('firewall.deleteRule')
def op_firewall_delete_rule(args):
    tail = _fw_spec(args, with_comment=False)
    argv = [_fw_argv(), '--force', 'delete'] + tail
    return merge(run(argv, timeout=45), {'spec': tail, 'command': ' '.join(argv)})


@op('firewall.deleteByNo')
def op_firewall_delete_by_no(args):
    no = args.get('no')
    try:
        no = int(no)
    except (TypeError, ValueError):
        raise OpError('bad-request', '规则编号必须是整数')
    if not 1 <= no <= 5000:
        raise OpError('bad-request', '规则编号超出范围')
    argv = [_fw_argv(), '--force', 'delete', str(no)]
    return merge(run(argv, timeout=45), {'no': no, 'command': ' '.join(argv)})


@op('firewall.setEnabled')
def op_firewall_set_enabled(args):
    enabled = args.get('enabled')
    if not isinstance(enabled, bool):
        raise OpError('bad-request', 'enabled 必须是布尔值')
    argv = [_fw_argv(), '--force', 'enable' if enabled else 'disable']
    return merge(run(argv, timeout=60), {'command': ' '.join(argv)})


@op('firewall.setDefault')
def op_firewall_set_default(args):
    ufw = _fw_argv()
    results = {}
    for direction in ('incoming', 'outgoing', 'routed'):
        policy = args.get(direction)
        if policy is None:
            continue
        if policy not in FW_POLICIES:
            raise OpError('bad-request', '%s 策略仅支持 allow/deny/reject' % direction)
        r = run([ufw, 'default', policy, direction], timeout=30)
        results[direction] = {'policy': policy, 'exitCode': r['exitCode'],
                              'stdout': r['stdout'], 'stderr': r['stderr']}
    if not results:
        raise OpError('bad-request', '至少要指定一个方向的默认策略')
    return ok(data={'results': results})


@op('firewall.reload')
def op_firewall_reload(args):
    return run([_fw_argv(), 'reload'], timeout=60)


@op('firewall.version')
def op_firewall_version(args):
    return run([_fw_argv(), '--version'], timeout=15)


# ---------------------------- nginx ---------------------------- #

def _nginx_argv():
    return require_tool('nginx')


def _certbot_path():
    return tool('certbot')


def _certbot_issue_argv(args):
    domain = str(args.get('domain') or '').strip()
    email = str(args.get('email') or '').strip()
    webroot = str(args.get('webroot') or '').strip()
    config_dir = str(args.get('configDir') or '/etc/letsencrypt').strip()
    if not domain:
        raise OpError('bad-request', '缺少 domain')
    domains = [d.strip() for d in domain.split(',') if d.strip()]
    if not domains:
        raise OpError('bad-request', 'domain 非法')
    for d in domains:
        if not re.match(r'^(\*\.)?[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$', d):
            raise OpError('bad-request', '域名非法: %s' % d)
    if not webroot or webroot.startswith('-') or '..' in webroot:
        raise OpError('bad-request', 'webroot 非法')
    if '..' in config_dir or not config_dir.startswith('/'):
        raise OpError('bad-request', 'configDir 非法')
    cb = _certbot_path()
    if not cb:
        raise OpError('tool-missing', '宿主机缺少 certbot')
    argv = [cb, 'certonly', '--webroot', '-w', webroot,
            '--email', email or 'admin@localhost', '--agree-tos',
            '--non-interactive', '--no-eff-email',
            '--config-dir', config_dir + '/le',
            '--work-dir', config_dir + '/le/work',
            '--logs-dir', config_dir + '/le/logs']
    for d in domains:
        argv += ['-d', d]
    return argv


@op('nginx.detect')
def op_nginx_detect(args):
    nginx = tool('nginx')
    if not nginx:
        return ok(data={'found': False, 'reason': 'nginx 未安装'})
    ver = run([nginx, '-V'], timeout=15)
    blob = (ver.get('stderr') or '') + '\n' + (ver.get('stdout') or '')

    def _arg(name):
        m = re.search(r'--%s=(\S+)' % re.escape(name), blob)
        return m.group(1).rstrip(',') if m else None

    version = None
    m = re.search(r'nginx version: nginx/([\d.]+)', blob)
    if m:
        version = m.group(1)
    certbot = None
    cb = tool('certbot')
    if cb:
        r = run([cb, '--version'], timeout=15)
        mm = re.search(r'(\d+\.\d+(?:\.\d+)?)', (r.get('stdout') or '') + (r.get('stderr') or ''))
        if mm:
            certbot = mm.group(1)
    prefix = _arg('prefix')
    # nginx 只在编译时给了 --prefix 时，其余路径走默认派生规则（BT nginx 即如此）
    def _or_default(val, tail):
        return val if val else (os.path.join(prefix, tail) if prefix else None)
    return ok(data={
        'found': True,
        'binary': nginx,
        'version': version,
        'prefix': prefix,
        'confPath': _or_default(_arg('conf-path'), 'conf/nginx.conf'),
        'pidPath': _or_default(_arg('pid-path'), 'logs/nginx.pid'),
        'errorLog': _or_default(_arg('error-log-path'), 'logs/error.log'),
        'httpLog': _or_default(_arg('http-log-path'), 'logs/access.log'),
        'certbotVersion': certbot,
    })


@op('nginx.test')
def op_nginx_test(args):
    conf_path = str(args.get('confPath') or '').strip()
    argv = [_nginx_argv(), '-t']
    if conf_path:
        if not conf_path.startswith('/') or conf_path.startswith('-'):
            raise OpError('bad-request', 'confPath 必须是绝对路径')
        argv += ['-c', conf_path]
    return run(argv, timeout=30)


@op('nginx.reload')
def op_nginx_reload(args):
    return run([_nginx_argv(), '-s', 'reload'], timeout=30)


@op('nginx.certbotVersion')
def op_nginx_certbot_version(args):
    cb = _certbot_path()
    if not cb:
        return ok(data={'found': False})
    r = run([cb, '--version'], timeout=15)
    m = re.search(r'(\d+\.\d+(?:\.\d+)?)', (r.get('stdout') or '') + (r.get('stderr') or ''))
    return ok(r, data={'found': True, 'version': m.group(1) if m else None})


@op('nginx.acmeIssue')
def op_nginx_acme_issue(args):
    argv = _certbot_issue_argv(args)
    return merge(run(argv, timeout=120), {'command': ' '.join(argv)})


@op('nginx.acmeRenew')
def op_nginx_acme_renew(args):
    domain = str(args.get('domain') or '').strip()
    config_dir = str(args.get('configDir') or '/etc/letsencrypt').strip()
    cb = _certbot_path()
    if not cb:
        raise OpError('tool-missing', '宿主机缺少 certbot')
    argv = [cb, 'renew', '--cert-name', domain,
            '--non-interactive', '--quiet',
            '--config-dir', config_dir + '/le',
            '--work-dir', config_dir + '/le/work',
            '--logs-dir', config_dir + '/le/logs']
    return merge(run(argv, timeout=180), {'command': ' '.join(argv)})


@op('nginx.acmeStatus')
def op_nginx_acme_status(args):
    domain = str(args.get('domain') or '').strip()
    config_dir = str(args.get('configDir') or '/etc/letsencrypt').strip()
    live = os.path.join(config_dir, 'le', 'live', domain)
    if not os.path.isdir(live):
        return ok(data={'found': False})
    cert_file = os.path.join(live, 'fullchain.pem')
    not_after = None
    if os.path.isfile(cert_file):
        r = run(['openssl', 'x509', '-enddate', '-noout', '-in', cert_file], timeout=15)
        m = re.search(r'notAfter=(.+)', (r.get('stdout') or ''))
        if m:
            not_after = m.group(1).strip()
    return ok(data={'found': True, 'certPath': cert_file,
                    'keyPath': os.path.join(live, 'privkey.pem'),
                    'notAfter': not_after})



# ---------------------------- 看门狗（防锁死自动回滚） ---------------------------- #

def _pending_file(wid: str) -> str:
    return os.path.join(PENDING_DIR, '%s.json' % wid)


def _load_pending(wid: str):
    path = _pending_file(wid)
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _remove_pending(wid: str) -> bool:
    try:
        os.remove(_pending_file(wid))
        return True
    except OSError:
        return False


def _valid_watchdog_id(value) -> str:
    if not isinstance(value, str) or not WATCHDOG_ID_RE.match(value):
        raise OpError('bad-request', '看门狗 id 非法（仅字母数字与短横线，3-41 位）')
    return value


@op('watchdog.arm')
def op_watchdog_arm(args):
    wid = _valid_watchdog_id(args.get('id'))
    try:
        seconds = int(args.get('seconds') or 300)
    except (TypeError, ValueError):
        raise OpError('bad-request', 'seconds 必须是整数')
    if not 30 <= seconds <= 3600:
        raise OpError('bad-request', 'seconds 需在 30-3600 之间')
    undo = args.get('undo') or []
    if not isinstance(undo, list) or len(undo) > 20:
        raise OpError('bad-request', 'undo 必须是数组且不超过 20 项')
    for item in undo:
        if not isinstance(item, dict) or item.get('op') not in WATCHDOG_OPS:
            raise OpError('bad-request', 'undo 只允许防火墙类 op')

    os.makedirs(PENDING_DIR, exist_ok=True)
    unit = 'psm-fw-rollback-%s' % wid
    expires = int(time.time()) + seconds
    record = {
        'id': wid,
        'unit': unit,
        'reason': str(args.get('reason') or '')[:200],
        'undo': undo,
        'armedAt': _now_iso(),
        'expiresAt': expires,
        'seconds': seconds,
        'operator': str(args.get('operator') or '')[:64],
    }
    tmp = _pending_file(wid) + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(record, f, ensure_ascii=False)
    os.replace(tmp, _pending_file(wid))

    systemd_run = require_tool('systemd-run')
    argv = [systemd_run,
            '--on-active=%d' % seconds,
            '--unit=%s' % unit,
            '--collect',
            '--description=ServerPanel 防火墙变更自动回滚 (%s)' % wid,
            tool('python3') or '/usr/bin/python3', SELF_PATH, 'rollback', wid]
    result = run(argv, timeout=30)
    if result['exitCode'] != 0:
        _remove_pending(wid)
        raise OpError('systemd-run-failed',
                      '注册回滚定时器失败: %s' % (result['stderr'] or result['stdout']).strip())
    return merge(result, {'id': wid, 'unit': unit, 'expiresAt': expires, 'seconds': seconds,
                          'command': ' '.join(argv)})


@op('watchdog.status')
def op_watchdog_status(args):
    wid = _valid_watchdog_id(args.get('id'))
    record = _load_pending(wid)
    if not record:
        return ok(data={'armed': False})
    unit = record.get('unit') or ('psm-fw-rollback-%s' % wid)
    timer_active = False
    systemctl = tool('systemctl')
    if systemctl:
        r = run([systemctl, 'is-active', '%s.timer' % unit], timeout=10)
        timer_active = (r.get('stdout') or '').strip() == 'active'
    return ok(data={'armed': True, 'timerActive': timer_active, 'unit': unit,
                    'reason': record.get('reason'), 'expiresAt': record.get('expiresAt'),
                    'secondsLeft': max(0, int(record.get('expiresAt', 0)) - int(time.time()))})


@op('watchdog.list')
def op_watchdog_list(args):
    items = []
    if os.path.isdir(PENDING_DIR):
        for name in sorted(os.listdir(PENDING_DIR)):
            if not name.endswith('.json'):
                continue
            record = _load_pending(name[:-5])
            if record:
                items.append({'id': record.get('id'), 'reason': record.get('reason'),
                              'expiresAt': record.get('expiresAt'),
                              'secondsLeft': max(0, int(record.get('expiresAt', 0)) - int(time.time()))})
    return ok(data={'items': items})


@op('watchdog.confirm')
def op_watchdog_confirm(args):
    wid = _valid_watchdog_id(args.get('id'))
    record = _load_pending(wid)
    unit = (record or {}).get('unit') or ('psm-fw-rollback-%s' % wid)
    systemctl = tool('systemctl')
    steps = []
    if systemctl:
        for argv in ([systemctl, 'stop', '%s.timer' % unit],
                     [systemctl, 'reset-failed', '%s.service' % unit, '%s.timer' % unit]):
            r = run(argv, timeout=20)
            steps.append({'command': ' '.join(argv), 'exitCode': r['exitCode'],
                          'stderr': r['stderr']})
    removed = _remove_pending(wid)
    return ok(data={'id': wid, 'unit': unit, 'removed': removed, 'steps': steps})


def execute_undo(record: dict) -> list:
    """逆序执行 undo 脚本，记录每一步结果。"""
    steps = []
    for item in reversed(record.get('undo') or []):
        name = item.get('op')
        optional = bool(item.get('optional'))
        handler = OPS.get(name)
        if handler is None or name not in WATCHDOG_OPS:
            steps.append({'op': name, 'skipped': True, 'reason': 'op 不在白名单'})
            continue
        try:
            result = handler(item.get('args') or {})
            steps.append({'op': name, 'exitCode': result.get('exitCode'),
                          'stderr': (result.get('stderr') or '')[:500],
                          'stdout': (result.get('stdout') or '')[:500]})
        except OpError as exc:
            steps.append({'op': name, 'error': exc.message, 'optional': optional})
        except Exception as exc:                             # noqa: BLE001
            steps.append({'op': name, 'error': str(exc), 'optional': optional})
    return steps


def cli_rollback(wid: str) -> int:
    wid = _valid_watchdog_id(wid)
    record = _load_pending(wid)
    if not record:
        log_event({'event': 'rollback', 'id': wid, 'result': 'no-pending'})
        return 0
    steps = execute_undo(record)
    log_event({'event': 'rollback', 'id': wid, 'reason': record.get('reason'),
               'steps': steps, 'result': 'done'})
    _remove_pending(wid)
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        with open(os.path.join(LOG_DIR, 'rollback-%s.json' % wid), 'w',
                  encoding='utf-8') as f:
            json.dump({'id': wid, 'at': _now_iso(), 'steps': steps}, f, ensure_ascii=False)
    except OSError:
        pass
    return 0


# --------------------------------------------------------------------------- #
# 请求处理
# --------------------------------------------------------------------------- #

def read_secret() -> str:
    try:
        with open(SECRET_FILE, encoding='utf-8') as f:
            return f.read().strip()
    except OSError:
        return ''


def verify_secret(provided: str) -> bool:
    expected = read_secret()
    if not expected:
        return False
    return hmac.compare_digest(provided.encode('utf-8'), expected.encode('utf-8'))


def handle_request(req: dict) -> dict:
    if not isinstance(req, dict):
        return {'ok': False, 'code': 'bad-request', 'message': '请求必须是 JSON 对象'}
    name = req.get('op')
    if not isinstance(name, str):
        return {'ok': False, 'code': 'bad-request', 'message': '缺少 op'}
    if not verify_secret(req.get('secret') or ''):
        return {'ok': False, 'code': 'unauthorized', 'message': '鉴权失败'}
    handler = OPS.get(name)
    if handler is None:
        return {'ok': False, 'code': 'unknown-op', 'message': '不支持的 op: %s' % name}
    args = req.get('args') or {}
    if not isinstance(args, dict):
        return {'ok': False, 'code': 'bad-request', 'message': 'args 必须是对象'}
    started = time.time()
    try:
        result = handler(args)
    except OpError as exc:
        log_event({'op': name, 'ok': False, 'code': exc.code, 'message': exc.message,
                   'client': _client_id(), 'durationMs': int((time.time() - started) * 1000)})
        return {'ok': False, 'code': exc.code, 'message': exc.message}
    except Exception as exc:                                 # noqa: BLE001
        log_event({'op': name, 'ok': False, 'code': 'internal', 'message': str(exc),
                   'client': _client_id(), 'durationMs': int((time.time() - started) * 1000)})
        return {'ok': False, 'code': 'internal', 'message': '代理内部错误: %s' % exc}
    body = dict(result or {})
    body['ok'] = True
    body['op'] = name
    body.setdefault('durationMs', int((time.time() - started) * 1000))
    if name not in ('service.listUnits', 'service.listUnitFiles', 'firewall.statusRaw',
                    'service.logs', 'service.show'):
        log_event({'op': name, 'ok': True, 'exitCode': body.get('exitCode'),
                   'client': _client_id(), 'durationMs': body.get('durationMs')})
    return body


CLIENT_ID = {'value': '-'}


def _client_id() -> str:
    return CLIENT_ID['value']


class Handler(socketserver.StreamRequestHandler):
    timeout = 120

    def handle(self):
        try:
            raw = self.rfile.readline(MAX_REQUEST_BYTES + 1)
        except OSError:
            return
        if not raw:
            return
        if len(raw) > MAX_REQUEST_BYTES:
            self._write({'ok': False, 'code': 'too-large', 'message': '请求体过大'})
            return
        try:
            req = json.loads(raw.decode('utf-8'))
        except (ValueError, UnicodeDecodeError):
            self._write({'ok': False, 'code': 'bad-json', 'message': '请求不是合法 JSON'})
            return
        peer = ''
        try:
            peer = 'pid=%s uid=%s' % (self.request.getsockopt(0, 0) and '', '')
        except OSError:
            peer = ''
        CLIENT_ID['value'] = peer or 'unix'
        self._write(handle_request(req))

    def _write(self, payload: dict):
        try:
            self.wfile.write((json.dumps(payload, ensure_ascii=False, default=str) + '\n')
                             .encode('utf-8'))
            self.wfile.flush()
        except OSError:
            pass


class ThreadedUnixServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    request_queue_size = 64
    allow_reuse_address = True


def prepare_socket_dir():
    os.makedirs(RUN_DIR, exist_ok=True)
    try:
        os.chmod(RUN_DIR, 0o755)
    except OSError:
        pass


def serve() -> int:
    prepare_socket_dir()
    if os.path.exists(SOCKET_PATH):
        try:
            os.remove(SOCKET_PATH)
        except OSError:
            pass
    server = ThreadedUnixServer(SOCKET_PATH, Handler)
    try:
        os.chmod(SOCKET_PATH, 0o660)
    except OSError:
        pass
    try:
        import grp
        grp_id = grp.getgrnam(SOCKET_GROUP).gr_gid
        os.chown(SOCKET_PATH, 0, grp_id)
    except (KeyError, OSError, ImportError):
        pass
    log_event({'event': 'start', 'version': VERSION, 'socket': SOCKET_PATH,
               'secretFile': SECRET_FILE, 'group': SOCKET_GROUP})
    try:
        server.serve_forever(poll_interval=1.0)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        try:
            os.remove(SOCKET_PATH)
        except OSError:
            pass
    return 0


def main() -> int:
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 2
    command = argv[0]
    if command == 'serve':
        return serve()
    if command == 'probe':
        result = op_host_probe({})
        print(json.dumps({k: v for k, v in result.items() if k != 'stdout'},
                         ensure_ascii=False, indent=2))
        return 0
    if command == 'rollback':
        if len(argv) < 2:
            print('用法: hostagent.py rollback <id>', file=sys.stderr)
            return 2
        return cli_rollback(argv[1])
    print('未知命令: %s' % command, file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main())
