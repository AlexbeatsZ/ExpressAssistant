"""淘宝/菜鸟：纯 HTTP，移植自 App TbOrders + SyncEngine.fetchTbAll。

1) mtop.taobao.order.queryboughtlistv2（wapSign：md5(token&t&appKey&原始data)）拿订单；
2) 逐单请求 SSR 物流详情页，解析承运商 + 运单号 + 全量轨迹。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
import time
from urllib.parse import quote

import aiohttp

from ..parse import cookie_header, eta_from

log = logging.getLogger("taobao")

APPKEY = "12574478"
UA = ("Mozilla/5.0 (Linux; Android 12; M2102K1C) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/105.0.0.0 Mobile Safari/537.36 EdgA/105.0.1343.48")
SSR = "https://pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail?x-ssr=true&bizOrderId="
DOMAINS = ("taobao.com", "tmall.com")


async def _mtop(s: aiohttp.ClientSession, cookies: str, api: str, data_raw: str) -> tuple[str, str]:
    base = f"https://h5api.m.taobao.com/h5/{api}/1.0/"
    headers = {"User-Agent": UA, "Origin": "https://h5.m.taobao.com", "Referer": "https://h5.m.taobao.com/",
               "Accept": "application/json, text/plain, */*", "Accept-Language": "zh-CN,zh;q=0.9"}
    ck = cookies
    # 1) 刷新 _m_h5_tk（手动管理 Cookie：CookieJar 与显式头合并会出现双 _m_h5_tk，被当游客）
    try:
        async with s.get(f"{base}?appKey={APPKEY}", headers={**headers, "Cookie": ck}) as r:
            for line in r.headers.getall("Set-Cookie", []):
                for name in ("_m_h5_tk", "_m_h5_tk_enc"):
                    m = re.match(rf"\s*{name}=([^;]+)", line)
                    if m:
                        if re.search(rf"(^|; ){name}=", ck):
                            ck = re.sub(rf"(^|; ){name}=[^;]*", rf"\g<1>{name}={m.group(1)}", ck)
                        else:
                            ck += f"; {name}={m.group(1)}"
    except aiohttp.ClientError as e:
        log.warning("mtop token refresh fail: %s", e)
    m = re.search(r"_m_h5_tk=([^;]+)", ck)
    if not m:
        raise RuntimeError("淘宝登录已失效（缺少 _m_h5_tk），请重新登录")
    token = m.group(1).split("_")[0]
    t = str(int(time.time() * 1000))
    sign = hashlib.md5(f"{token}&{t}&{APPKEY}&{data_raw}".encode()).hexdigest()
    url = (f"{base}?jsv=2.3.18&appKey={APPKEY}&t={t}&sign={sign}"
           f"&type=jsonp&dataType=jsonp&data={quote(data_raw, safe='')}")
    async with s.get(url, headers={**headers, "Cookie": ck}) as r:
        return await r.text(), ck


def _parse_bought(body: str) -> list[dict]:
    i, j = body.find("("), body.rfind(")")
    if i < 0 or j <= i:
        return []
    root = json.loads(body[i + 1:j])
    ret = " ".join(root.get("ret") or [])
    if "SESSION_EXPIRED" in ret or "FAIL_SYS_TOKEN" in ret:
        raise RuntimeError("淘宝登录已失效，请重新登录")
    data = root.get("data") or {}
    inner = None
    if isinstance(data.get("result"), str):
        try:
            inner = json.loads(data["result"])
        except ValueError:
            inner = None
    inner = inner or data.get("data") or {}
    orders = []
    for o in inner.get("mainOrders") or []:
        item = ((o.get("subOrders") or [{}])[0] or {}).get("itemInfo") or {}
        orders.append({
            "orderId": str(o.get("id", "")),
            "tradeStatus": (o.get("extra") or {}).get("tradeStatus", ""),
            "statusText": (o.get("statusInfo") or {}).get("text", ""),
            "goodsName": item.get("title", ""), "goodsPic": item.get("pic", ""),
        })
    if not orders:
        for k, v in inner.items():
            if k.startswith("Main_") and isinstance(v, dict):
                f = v.get("fields") or {}
                oid = f.get("orderId") or k[5:]
                orders.append({"orderId": str(oid), "tradeStatus": "", "statusText": f.get("orderStatus", "")})
    return orders


def _ssr_logistics(html: str) -> dict | None:
    marker = "__ICE_SUSPENSE_LOADER__']['undefined'] = "
    i = html.find(marker)
    if i < 0:
        return None
    try:
        obj, _ = json.JSONDecoder().raw_decode(html[i + len(marker):])
    except ValueError:
        return None
    data = ((obj.get("result") or {}).get("data")) or {}
    return ((data.get("newLogistics") or {}).get("fields")
            or (data.get("logisticsDetailH5") or {}).get("fields"))


def _stage_text(st: dict) -> str:
    ld = st.get("labelDesc")
    out = ""
    if isinstance(ld, dict):
        rich = ld.get("richContent")
        out = "".join((r or {}).get("text", "") for r in rich) if isinstance(rich, list) else ld.get("text", "")
    elif isinstance(ld, str) and not ld.startswith("{"):
        out = ld
    return out or st.get("text", "")


def _parse_ssr(html: str, order_id: str) -> tuple[dict, list[dict]] | None:
    lg = _ssr_logistics(html)
    if not lg:
        return None
    company = lg.get("logisticCompany") or {}
    mail = lg.get("mailNo") or company.get("mailNo") or ""
    if not mail:
        return None
    stages = lg.get("multiStage") or []
    points = []
    for st in stages:
        desc = _stage_text(st)
        if desc:
            points.append({"time": st.get("subtitle") or st.get("time", ""), "context": desc})
    first = stages[0] if stages else {}
    parcel = {
        "mailNo": mail, "cpName": company.get("name", ""), "stateLabel": first.get("title", ""),
        "latestText": _stage_text(first) if first else "", "latestTime": (first.get("subtitle") or first.get("time", "")) if first else "",
        "orderId": order_id,
    }
    # SSR 的 multiStage 最新在前；统一存为时间正序
    return parcel, list(reversed(points))


def _to_item(p: dict, account: dict) -> dict:
    label = p["stateLabel"]
    if "签收" in label or "成功" in label:
        state = 3
    elif "派送" in label:
        state = 5
    elif "揽收" in label or ("发货" in label and "待发货" not in label):
        state = 2
    elif "下单" in label or "待发货" in label:
        state = 1
    else:
        state = 0
    num = {3: 107, 5: 105, 2: 104, 1: 103}.get(state, 105)
    return {
        "id": p["mailNo"], "mailNo": p["mailNo"], "companyCode": "", "companyName": p["cpName"] or "淘宝快递",
        "latestText": p["latestText"], "latestTime": p["latestTime"], "state": state, "stateName": label,
        "stateNum": num, "provider": "CaiNiao", "eta": eta_from(p["latestText"], label),
        "queryChannel": p["orderId"], "source": "taobao", "accountId": account["id"], "accountLabel": account.get("label", ""),
    }


async def fetch(account: dict, cookies_list: list[dict]) -> tuple[list[dict], dict, dict]:
    """返回 (items, goods{mailNo}, traces{mailNo})。"""
    cookies = cookie_header(cookies_list, *DOMAINS)
    if not cookies:
        raise RuntimeError("淘宝账号未登录")
    timeout = aiohttp.ClientTimeout(total=25)
    async with aiohttp.ClientSession(timeout=timeout, cookie_jar=aiohttp.DummyCookieJar()) as s:
        orders: dict[str, dict] = {}
        for page in range(1, 4):
            data = json.dumps({
                "tabCode": "all", "page": page, "OrderType": "OrderList", "templateConfigVersion": "0",
                "appName": "tborder", "appVersion": "3.0", "condition": '{"version":"1.0.0","appChannel":""}',
                "ttid": "201200@taobao_h5_9.18.0", "requestIdentity": "#t#ip#h5",
            }, ensure_ascii=False, separators=(",", ":"))
            body, cookies = await _mtop(s, cookies, "mtop.taobao.order.queryboughtlistv2", data)
            batch = _parse_bought(body)
            for o in batch:
                orders.setdefault(o["orderId"], o)
            if len(batch) < 10:
                break
        with_parcels = [o for o in orders.values()
                        if "CLOSED" not in o["tradeStatus"] and o["statusText"] not in ("交易关闭", "交易成功（未发货）")]
        log.info("taobao(%s) orders=%d withParcels=%d", account.get("label"), len(orders), len(with_parcels))
        sem = asyncio.Semaphore(4)
        headers = {"User-Agent": UA, "Accept": "text/html,*/*;q=0.8", "Accept-Language": "zh-CN,zh;q=0.9", "Cookie": cookies}

        async def one(o: dict):
            async with sem:
                try:
                    async with s.get(SSR + o["orderId"], headers=headers) as r:
                        return o, _parse_ssr(await r.text(), o["orderId"])
                except (aiohttp.ClientError, asyncio.TimeoutError) as e:
                    log.warning("ssr %s fail: %s", o["orderId"], e)
                    return o, None

        results = await asyncio.gather(*(one(o) for o in with_parcels))
    items, goods, traces = [], {}, {}
    for o, parsed in results:
        if not parsed:
            continue
        parcel, points = parsed
        items.append(_to_item(parcel, account))
        if points:
            traces[parcel["mailNo"]] = points
        if o.get("goodsName"):
            pic = o.get("goodsPic", "")
            goods[parcel["mailNo"]] = {"name": o["goodsName"], "imageUrl": f"https:{pic}" if pic.startswith("//") else pic, "count": ""}
    return items, goods, traces


def label_from_cookies(cookies: dict[str, str]) -> tuple[str, str]:
    """(key, label)：key 用 unb 去重，label 优先昵称。"""
    from urllib.parse import unquote

    key = cookies.get("unb") or cookies.get("cookie1", "")[:6]
    nick = unquote(cookies.get("_nk_") or cookies.get("tracknick") or "")
    nick = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), nick)
    return key, nick or (f"淘宝 {key[-4:]}" if key else "淘宝账号")
