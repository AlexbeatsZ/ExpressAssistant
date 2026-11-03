"""京东：无头浏览器加载订单中心，页面自己完成 h5st 签名，读取 order_list_m 响应。

移植自 App JdListFetcher（列表）与 JdTrackFetcher（deal_wuliu 物流页 DOM 轨迹）。
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from pathlib import Path
from urllib.parse import unquote

from playwright.async_api import Page

from ..browser import BrowserManager, swipe_up

log = logging.getLogger("jd")

ORDER_CENTER = "https://trade.m.jd.com/order/orderlist_jdm.shtml?orderType=all&source=m_outer_jx_order"
LOGIN_URL = "https://plogin.m.jd.com/login/login?appid=300&returnurl=https%3A%2F%2Fhome.m.jd.com%2FmyJd%2Fhome.action"
BAD = ("取消", "关闭", "退款", "售后", "待付款", "未支付")


def _jsonp(text: str) -> dict | None:
    t = text.strip()
    if not t.startswith("{"):
        i, j = t.find("("), t.rfind(")")
        if i < 0 or j <= i:
            return None
        t = t[i + 1:j]
    try:
        v = json.loads(t)
        return v if isinstance(v, dict) else None
    except ValueError:
        return None


def _convert(bodies: list[dict], account: dict) -> tuple[dict[str, dict], dict[str, dict]]:
    items: dict[str, dict] = {}
    goods: dict[str, dict] = {}
    for root in bodies:
        for o in ((root.get("body") or {}).get("orderList") or []):
            oid = str(o.get("orderId") or "")
            if not oid:
                continue
            status = o.get("orderStatusInfo") or {}
            name = status.get("orderStatusName", "")
            if any(b in name for b in BAD):
                continue
            prog = o.get("progressInfo") or {}
            latest = prog.get("content") or status.get("stateTip") or name
            wares = o.get("wareInfoList") or []
            link = prog.get("progressLink") or (
                "https://trade.m.jd.com/order/deal_wuliu_jdm.shtml?from=orderdetail"
                f"&dealState={status.get('originOrderStatus', '')}&dealId={oid}&orderType={o.get('orderType', '')}"
                f"&skuid={(wares[0] if wares else {}).get('skuId', '')}&shopid={(o.get('shopInfo') or {}).get('shopId', '')}"
                "&source=m_inner_orderList.track_orderTrack"
            )
            if "完成" in name or "签收" in name:
                state = 3
            elif "待收货" in name or "等待收货" in name:
                state = 5
            elif "待发货" in name or "出库" in name:
                state = 1
            else:
                state = 0
            items[oid] = {
                "id": oid, "mailNo": oid, "companyCode": "JDKD", "companyName": "京东商品快递",
                "latestText": latest, "latestTime": prog.get("tip", ""), "state": state, "stateName": name,
                "stateNum": {3: 107, 5: 105, 1: 103}.get(state, 105), "provider": "JingDong",
                "queryChannel": link, "source": "jd", "accountId": account["id"], "accountLabel": account.get("label", ""),
            }
            if wares:
                w0 = wares[0]
                goods[oid] = {"name": w0.get("wareName", ""), "imageUrl": w0.get("imageUrl", ""), "count": f"x{w0.get('num', 1)}"}
    return items, goods


def _total(bodies: list[dict]) -> int:
    for root in bodies:
        n = (root.get("body") or {}).get("totalNum") or 0
        if n:
            return int(n)
    return 0


async def fetch(bm: BrowserManager, account: dict, profile: Path) -> tuple[list[dict], dict]:
    """返回 (items, goods)。会话失效（跳到 plogin）时抛错，保留旧数据由上层决定。"""
    ctx = await bm.context(profile)
    bodies: list[dict] = []
    tasks: list[asyncio.Task] = []

    async def grab(resp):
        try:
            if "api.m.jd.com" in resp.url and "order_list_m" in resp.url:
                v = _jsonp(await resp.text())
                if v and (v.get("body") or {}).get("orderList") is not None:
                    bodies.append(v)
        except Exception:  # noqa: BLE001 - 页面关闭后读取失败可忽略
            pass

    try:
        page = await ctx.new_page()
        page.on("response", lambda r: tasks.append(asyncio.ensure_future(grab(r))))
        await page.goto(ORDER_CENTER, wait_until="domcontentloaded", timeout=45000)
        await asyncio.sleep(4)
        if "plogin" in page.url or "nopasswordcmcc" in page.url:
            raise RuntimeError("京东登录已失效或需要安全验证，请在服务器网页里重新登录京东")
        for rnd in range(15):
            items, _ = _convert(bodies, account)
            total = _total(bodies)
            log.info("jd round=%d orders=%d total=%d", rnd, len(items), total)
            if total and len(items) >= total:
                break
            await swipe_up(page)
            await asyncio.sleep(2.5)
        await asyncio.gather(*tasks, return_exceptions=True)
        await ctx.storage_state(path=str(profile))
        items, goods = _convert(bodies, account)
        if not bodies:
            raise RuntimeError("京东订单列表没有返回数据")
        return list(items.values()), goods
    finally:
        await ctx.close()


_TIME_RE = re.compile(r"^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?)")
_NOISE = {"订单跟踪", "联系客服", "复制", "展开详细信息", "仓库", "签", "派", "等待收货", "已发货"}
_SKIP = re.compile(r"(订单编号|运单号|国内承运人|联系电话|^\d+件$|¥|评论|已售)")
_KW = ("快件", "包裹", "派件", "揽收", "签收", "发往", "到达", "转运", "投递", "取件", "出库", "配送", "妥投", "物流", "运输")


def parse_trace_text(text: str) -> list[dict]:
    """deal_wuliu 页「描述在上、时间在下」：描述累积，遇时间行提交。"""
    points, pending = [], None
    for line in text.split("\n"):
        line = line.strip()
        if not line:
            continue
        m = _TIME_RE.match(line)
        if m and len(line) <= 25:
            d = (pending or "").strip()
            if len(d) >= 4:
                points.append({"time": m.group(1), "context": d})
            pending = None
            continue
        if line in _NOISE or _SKIP.search(line):
            continue
        if pending is None:
            if "【" in line or any(k in line for k in _KW):
                pending = line
        else:
            pending += " " + line
    # 页面最新在上：转为时间正序
    return sorted(points, key=lambda p: p["time"])


async def fetch_trace(page: Page, item: dict) -> list[dict]:
    link = item.get("queryChannel") or ""
    if "deal_wuliu" in link:
        url = f"https:{link}" if link.startswith("//") else link
    else:
        url = f"https://trade.m.jd.com/order/deal_wuliu_jdm.shtml?dealId={item['mailNo']}"
    await page.goto(url, wait_until="domcontentloaded", timeout=30000)
    await asyncio.sleep(5)
    text = await page.evaluate("() => document.body ? document.body.innerText.slice(0, 20000) : ''")
    return parse_trace_text(text)


def label_from_cookies(cookies: dict[str, str]) -> tuple[str, str]:
    pin = unquote(cookies.get("pt_pin", ""))
    return pin, pin or "京东账号"
