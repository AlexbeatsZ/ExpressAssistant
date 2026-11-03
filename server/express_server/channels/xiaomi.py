"""小米智能助理「我的快递」：纯 HTTP，移植自 App XiaomiApi / XiaomiPassport / XiaomiSync。

PC 上没有 MIUI 的 CoderUtils 与设备标识，加解密走 App 的同款 AES 兜底，
oaid/vaid 用每个账号首次登录时生成并持久化的随机值。
"""

from __future__ import annotations

import base64
import hashlib
import json
import logging
import re
import uuid
from datetime import datetime
from urllib.parse import quote

import aiohttp
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from ..parse import eta_from

log = logging.getLogger("xiaomi")

HOST = "https://verca.xpa.assistant.miui.com"
AES_KEY = b"d101b17c77ff93cs"
SIGN_SALT = "77eb2e8a5755abd016c0d69ba74b219c"
PA_VERSION = 25000031
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
LOGIN_PROBE = "https://account.xiaomi.com/pass/serviceLogin?sid=assistant&_json=true"


def _aes_enc(plain: str) -> str:
    padder = padding.PKCS7(128).padder()
    data = padder.update(plain.encode()) + padder.finalize()
    enc = Cipher(algorithms.AES(AES_KEY), modes.ECB()).encryptor()
    return base64.b64encode(enc.update(data) + enc.finalize()).decode()


def _aes_dec(text: str) -> str:
    dec = Cipher(algorithms.AES(AES_KEY), modes.ECB()).decryptor()
    raw = dec.update(base64.b64decode(text)) + dec.finalize()
    unpadder = padding.PKCS7(128).unpadder()
    return (unpadder.update(raw) + unpadder.finalize()).decode()


def new_device_ids() -> dict:
    return {"oaid": uuid.uuid4().hex[:16], "vaid": uuid.uuid4().hex[:16]}


def _wrap(body: dict, user_id: str, cred: dict) -> str:
    env = {
        "terminal": "phone", "assistantVersion": PA_VERSION, "launchVersion": 0,
        "androidVersion": "14", "androidSdkVersion": 34, "miuiType": "stable",
        "phoneModel": "23127PN0CC", "phoneDevice": "shennong", "connectionType": "WIFI",
        "miuiVersionName": "V816", "miuiVersion": "816", "osVersionName": "OS1.0", "osVersion": "1",
        "miSettingsVersionName": "", "miSettingsVersionCode": 0, "timeZone": "GMT+08:00",
        "language": "zh", "country": "CN", "screen": 1, "coordinate": "",
        "reqId": uuid.uuid4().hex, "ip": "", "cpuArchitecture": "arm64-v8a", "marketVersion": 0,
        "os": "", "clientFlag": 0, "hwVersion": "",
    }
    return json.dumps({
        "userSignal": {"oaid": cred.get("oaid", ""), "vaid": cred.get("vaid", ""), "userId": user_id},
        "environmentSignal": env,
        "timeSignal": {"clientTs": str(int(datetime.now().timestamp() * 1000)), "timeZone": "GMT+08:00"},
        "eventSignal": body,
    }, ensure_ascii=False, separators=(",", ":"))


async def _post(session: aiohttp.ClientSession, path: str, cred: dict, body: dict) -> dict:
    enc = _aes_enc(path)
    sign = hashlib.sha1(f"yellowpage_encparam{enc}{SIGN_SALT}".encode()).hexdigest().upper()
    url = (f"{HOST}{path}?version={PA_VERSION}&appkey=yellowpage&yellowpage_encparam={quote(enc, safe='')}"
           f"&sign={sign}&_encparam={quote(enc, safe='')}")
    payload = _aes_enc(_wrap(body, cred.get("accountId") or cred["cUser"], cred))
    headers = {"Cookie": f"serviceToken={cred['token']}; cUserId={cred['cUser']}", "Content-Type": "application/json"}
    async with session.post(url, data=payload, headers=headers, timeout=aiohttp.ClientTimeout(total=25)) as r:
        text = await r.text()
        if r.status >= 400:
            raise RuntimeError(f"小米 HTTP {r.status}: {text[:200]}")
    root = json.loads(text)
    data = root.get("data")
    if isinstance(data, str) and data and data != "null":
        try:
            root["data"] = json.loads(_aes_dec(data))
        except Exception:  # noqa: BLE001 - 非加密 data 原样返回
            pass
    return root


def _state_to_int(state: str, num: int) -> int:
    if num == 107:
        return 3
    if num in (105, 106):
        return 5
    if num == 103:
        return 1
    if any(k in state for k in ("签收", "订单完成", "已送达")):
        return 3
    if "派送" in state or "配送" in state:
        return 5
    if "揽收" in state or "揽件" in state:
        return 1
    return 0


async def fetch(account: dict) -> tuple[list[dict], dict[str, list[dict]]]:
    """返回 (items, traces)；小米列表自带 details 全量轨迹，直接作为轨迹缓存。"""
    cred = account["payload"]
    if not cred.get("token"):
        raise RuntimeError("小米账号未登录")
    phones = cred.get("phones") or []
    if not phones:
        return [], {}
    body = {"info": {"limit": 29, "phones": phones, "deletedMailNos": [], "modifiedMailNos": []}}
    async with aiohttp.ClientSession() as s:
        root = await _post(s, "/cpa/express/v2/getList", cred, body)
    if root.get("code") != 0:
        raise RuntimeError(f"小米返回错误: {root.get('message') or root.get('desc') or root.get('code')}")
    data = root.get("data") or {}
    items, traces = [], {}
    for e in data.get("expressList") or []:
        details = e.get("details") or []
        latest = details[-1] if details else {}
        state = e.get("state", "")
        num = int(e.get("stateNum") or 0)
        mail = e.get("mailNo", "")
        if not mail:
            continue
        items.append({
            "id": mail, "mailNo": mail, "companyCode": e.get("cpCode", ""), "companyName": e.get("name", ""),
            "latestText": latest.get("desc") or state, "latestTime": latest.get("time", ""),
            "state": _state_to_int(state, num), "stateName": state, "stateNum": num,
            "phone": e.get("phone") or "", "provider": e.get("provider", ""), "iconUrl": e.get("iconUrl", ""),
            "eta": eta_from(" ".join(d.get("desc", "") for d in details), state),
            "source": "xiaomi", "accountId": account["id"], "accountLabel": account.get("label", ""),
        })
        traces[mail] = [{"time": d.get("time", ""), "context": d.get("desc", "")} for d in details if d.get("desc")]
    return items, traces


# ───────── 登录：passToken → serviceToken（移植 XiaomiPassport.mint） ─────────

async def login_url() -> str:
    async with aiohttp.ClientSession(headers={"User-Agent": UA}) as s:
        async with s.get(LOGIN_PROBE) as r:
            text = await r.text()
    obj = json.loads(text[text.index("{"):])
    if not obj.get("location"):
        raise RuntimeError(f"小米登录地址获取失败: {text[:200]}")
    return obj["location"]


async def mint(pass_token: str, user_id: str, device_id: str) -> dict:
    cookie = f"userId={quote(user_id)}; passToken={quote(pass_token)}; deviceId={quote(device_id)}"
    url = ("https://account.xiaomi.com/pass/serviceLogin"
           "?sid=assistant&_json=true&_appName=com.miui.personalassistant&_locale=zh_CN")
    last: Exception | None = None
    for _ in range(2):
        try:
            async with aiohttp.ClientSession(headers={"User-Agent": UA, "Cookie": cookie}) as s:
                async with s.get(url) as r:
                    text = await r.text()
                obj = json.loads(text[text.index("{"):])
                if obj.get("code") != 0:
                    raise RuntimeError(f"小米登录失败: {obj.get('desc')}")
                client_sign = base64.b64encode(
                    hashlib.sha1(f"nonce={obj['nonce']}&{obj['ssecurity']}".encode()).digest()).decode()
                loc = obj["location"]
                sts = f"{loc}{'&' if '?' in loc else '?'}clientSign={quote(client_sign, safe='')}&_userIdNeedEncrypt=true"
                async with s.get(sts, allow_redirects=False) as r2:
                    set_cookies = r2.headers.getall("Set-Cookie", [])
            token = None
            for line in set_cookies:
                m = re.match(r"\s*(?:assistant_)?serviceToken=([^;]+)", line)
                if m:
                    token = m.group(1)
                    break
            if not token:
                raise RuntimeError("没有从小米取到令牌，请重新扫码登录")
            return {"token": token, "cUser": obj.get("cUserId", ""), "accountId": user_id}
        except Exception as e:  # noqa: BLE001 - App 同样重试一次
            last = e
    raise RuntimeError(str(last))


def phone_from_url(url: str) -> str:
    m = re.search(r"[?&](?:_user|user)=(1\d{10})", url)
    return m.group(1) if m else ""
