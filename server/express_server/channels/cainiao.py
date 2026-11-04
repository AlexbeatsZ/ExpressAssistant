"""菜鸟「我的包裹」取件码与在线身份码，走淘宝 H5 MTOP 网关（与淘宝渠道共用登录态与签名）。

依据菜鸟 Android 8.11.1023 的静态分析：
- 取件码：`packageStation.authCode`；值为占位 `needSms` 时回退 `feature.stationOrderPickUpCode`；仍是占位或为空则没有可展示的码。
- 在线身份码：响应 `model.identityCode` 即码内容，`model.expireTime` 是剩余秒数。
- App 本身调用的是带 `.cn` 后缀的原生接口（需要原生 SESSION/ECODE），H5 网关对它们返回「Session过期 / 入口未授权」；
  不带 `.cn` 的同名接口可以用淘宝 H5 会话调用。
"""

from __future__ import annotations

import json
import logging

import aiohttp

from ..parse import cookie_header, now_ms
from . import taobao

log = logging.getLogger("cainiao")

PDS_LIST = "mtop.cainiao.lpc.packageservice.queryPdsPackageDataList"
PCS_LIST = "mtop.cainiao.pcs.packageservice.queryPackageDataList"
IDENTITY = "mtop.cainiao.nbpickup.identitycode.get"
PAGE_SIZE = 50
MAX_PAGES = 20
SENTINELS = {"needsms", "need_sms", "-1", "0", "null", "none", "true", "false"}
DONE_STATUS = {"SIGN", "CANCEL", "REJECT", "RETURN", "LOST"}


async def _call(s: aiohttp.ClientSession, cookies: str, api: str, data: dict) -> tuple[dict, str]:
    body, cookies = await taobao._mtop(s, cookies, api, json.dumps(data, ensure_ascii=False, separators=(",", ":")))
    i, j = body.find("("), body.rfind(")")
    root = json.loads(body[i + 1:j] if 0 <= i < j else body)
    ret = " ".join(root.get("ret") or [])
    if not ret.startswith("SUCCESS"):
        if "SESSION" in ret:
            raise RuntimeError("淘宝登录已失效，请重新登录淘宝")
        raise RuntimeError(f"菜鸟接口返回 {ret}")
    return root.get("data") or {}, cookies


def _feature(pkg: dict) -> dict:
    f = pkg.get("featureObj")
    if isinstance(f, dict) and f:
        return f
    try:
        f = json.loads(pkg.get("feature") or "{}")
    except ValueError:
        return {}
    return f if isinstance(f, dict) else {}


def _usable(code) -> str:
    c = str(code or "").strip()
    return "" if c.lower() in SENTINELS else c


def pickup_code(pkg: dict) -> str:
    """App 规则：authCode 优先；只有它是 needSms 占位时才用 stationOrderPickUpCode；终值不能是占位。"""
    station = pkg.get("packageStation") or {}
    auth = str(station.get("authCode") or "").strip()
    if auth.lower() == "needsms":
        auth = str(_feature(pkg).get("stationOrderPickUpCode") or "").strip() or auth
    return _usable(auth)


def _state(status: str) -> tuple[int, int]:
    s = (status or "").upper()
    if s == "SIGN":
        return 3, 107
    if s in ("REJECT", "RETURN", "LOST", "CANCEL", "FAILED"):
        return 4, 108
    if "STA" in s or "DELIVER" in s or s in ("SENT_SCAN", "SENT_CITY"):
        return 5, 105
    if s in ("CREATE", "CONSIGN", "WAIT_ACCEPT"):
        return 1, 103
    return 0, 104


def to_item(pkg: dict, account: dict) -> dict:
    status = str(pkg.get("logisticsStatus") or "")
    state, num = _state(status)
    station = pkg.get("packageStation") or {}
    mail = str(pkg.get("mailNo") or "")
    return {
        "id": mail, "mailNo": mail, "companyCode": str(pkg.get("partnerCode") or ""),
        "companyName": str(pkg.get("partnerName") or "菜鸟包裹"),
        "latestText": str(pkg.get("lastLogisticDetail") or pkg.get("logisticsStatusDesc") or ""),
        "latestTime": str(pkg.get("logisticsGmtModified") or "")[:19],
        "state": state, "stateNum": num, "stateName": str(pkg.get("logisticsStatusDesc") or ""),
        "provider": "CaiNiao", "source": "cainiao",
        "pickupCode": "" if status.upper() in DONE_STATUS else pickup_code(pkg),
        "station": str(station.get("stationName") or ""),
        "stationAddress": str(station.get("stationDeliveryAddress") or ""),
        "accountId": account["id"], "accountLabel": account.get("label", ""),
    }


async def fetch(account: dict, cookies_list: list[dict]) -> list[dict]:
    """全部页（少一页就少一批取件码）；PDS 失败时走 PCS 备份接口。"""
    cookies = cookie_header(cookies_list, *taobao.DOMAINS)
    if not cookies:
        raise RuntimeError("淘宝账号未登录")
    out: list[dict] = []
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=25), cookie_jar=aiohttp.DummyCookieJar()) as s:
        for api in (PDS_LIST, PCS_LIST):
            out.clear()
            try:
                for page in range(1, MAX_PAGES + 1):
                    data, cookies = await _call(s, cookies, api, {
                        "pageSize": PAGE_SIZE, "currentPage": page, "needTemporality": False,
                        "needDynInfo": "{'needFindTemporality':true,'needAppointment':true}",
                    })
                    inner = data.get("data") if isinstance(data.get("data"), dict) else data
                    pkgs = [p for p in inner.get("packages") or [] if isinstance(p, dict) and p.get("mailNo")]
                    out += pkgs
                    if not pkgs or page >= int(inner.get("totalPage") or 1):
                        break
                break
            except RuntimeError as e:
                if api == PCS_LIST or "登录" in str(e):
                    raise
                log.warning("%s fail, fallback to pcs: %s", api, e)
    log.info("cainiao(%s) packages=%d codes=%d", account.get("label"), len(out), sum(1 for p in out if pickup_code(p)))
    return [to_item(p, account) for p in out]


async def identity_code(cookies_list: list[dict]) -> dict:
    """在线身份码：有 identityCode 才可出示；否则返回认证状态，交给官方入口开通。"""
    cookies = cookie_header(cookies_list, *taobao.DOMAINS)
    if not cookies:
        raise RuntimeError("需要先登录淘宝账号")
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20), cookie_jar=aiohttp.DummyCookieJar()) as s:
        data, _ = await _call(s, cookies, IDENTITY, {"appName": "GUOGUO", "verifyVersion": "V2"})
    model = data.get("model") or {}
    code = str(model.get("identityCode") or "").strip()
    flags = {k: str(model.get(k)).lower() == "true" for k in
             ("certificated", "hasNewCertificated", "simpleCertificated", "weakCertificated", "xnCertificated")}
    if not code:
        msg = str(model.get("msgInfo") or "").strip() or (
            "菜鸟没有为这个账号下发身份码（认证 / 授权状态均为否）。可以先在淘宝或菜鸟 App 里打开一次身份码并按提示完成授权，再回来重试")
        return {"available": False, "certified": flags, "message": msg}
    try:
        ttl = int(float(model.get("expireTime") or 0))
    except ValueError:
        ttl = 0
    return {
        "available": True, "code": code, "certified": flags,
        "expireAt": now_ms() + ttl * 1000 if ttl > 0 else None,
        "name": str(model.get("fullname") or ""),
    }
