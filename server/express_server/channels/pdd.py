"""拼多多：无头浏览器加载 H5 订单页，页面自己完成 anti_content 签名，读取 proxy/api 响应。

移植自 App PddListFetcher / PddTraceFetcher / PddCapture：字段随版本有差异，按「候选键 + 递归扫描」容错。
小程序下的单同样可见——前提是网页登录的手机号与小程序账号是同一个拼多多账号。
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from pathlib import Path

from playwright.async_api import Page

from ..browser import BrowserManager, click_text, swipe_up
from ..parse import first_string, first_string_deep, first_value_deep, time_string

log = logging.getLogger("pdd")

ORDER_PAGE = "https://mobile.yangkeduo.com/orders.html"
TRACE_PAGE = "https://mobile.yangkeduo.com/order.html?order_sn="
LOGIN_URL = "https://mobile.yangkeduo.com/login.html?_x_no_login_launch=1"

ORDER_KEYS = ("order_sn", "orderSn", "order_id", "orderId", "mall_order_sn", "order_no", "order_list_sn", "orderSnList")
TRACK_KEYS = ("tracking_no", "mailNo", "mail_no", "express_no", "logistics_no", "waybill_no", "waybillCode",
              "trackingNumber", "logistics_sn", "delivery_no")
COMPANY_KEYS = ("logistics_company_short_name", "logisticsCompanyName", "logistics_company_name", "shipping_company",
                "express_company_name", "company_name", "express_company")
STATUS_KEYS = ("order_status_prompt", "order_prompt", "status_prompt_text", "order_status_text",
               "combined_order_status_prompt", "status_desc", "statusDesc", "order_status_desc", "order_status",
               "orderStatus", "combined_order_status", "shipping_status", "status_text", "statusText",
               "logistics_status", "status")
TEXT_KEYS = ("order_status_prompt", "bottom_left_content", "logistics_desc", "tracking_desc", "logistics_desc_text",
             "status_desc", "statusDesc", "latest_status", "latest_status_text", "description", "desc", "text")
TIME_KEYS = ("logistics_update_time", "latest_time", "tracking_time", "update_time", "updateTime", "create_time",
             "gmt_modified", "status_change_time", "last_update_time")
GOODS_NAME_KEYS = ("goods_name", "goodsName", "goods_title", "goods_brief", "product_name", "item_name", "sku_name",
                   "goods_name_text")
GOODS_PIC_KEYS = ("goods_image_url", "goodsImageUrl", "image_url", "thumbnail", "pic", "image", "goods_img",
                  "goods_thumb_url", "thumb_url", "hd_thumb_url")
GOODS_NUM_KEYS = ("goods_num", "goodsCount", "quantity", "num", "count", "goods_number")
BAD = ("取消", "关闭", "退款", "售后", "待付款", "未支付", "已取消", "交易关闭")
PROMPTS = {"交易成功", "待评价", "已评价", "待发货", "待收货", "待成团", "待付款", "已取消", "退款成功", "交易关闭"}


def _relevant(url: str, body: str) -> bool:
    u, b = url.lower(), body[:4000].lower()
    return "proxy/api" in u and (
        any(k in u for k in ("order", "logistic", "express", "track", "parcel", "goods"))
        or any(k in b for k in ("tracking_no", "logistics_no", "mailno", "order_sn", "express"))
    )


def _is_login(url: str) -> bool:
    return "login.html" in url or "passport" in url or "/login" in url or "login?" in url


def _looks_like_order(o: dict) -> bool:
    for k in ORDER_KEYS + TRACK_KEYS:
        v = o.get(k)
        if isinstance(v, (str, int)) and not isinstance(v, bool) and str(v):
            return True
    return False


def _goods(o: dict) -> dict:
    name = first_string_deep(o, GOODS_NAME_KEYS, 6)
    if not name:
        return {}
    pic = first_string_deep(o, GOODS_PIC_KEYS, 6)
    if pic.startswith("//"):
        pic = "https:" + pic
    cnt = first_string_deep(o, GOODS_NUM_KEYS, 6)
    return {"name": name[:200], "imageUrl": pic, "count": f"x{cnt}" if cnt else ""}


def _parse_order(o: dict, out: dict, goods: dict, account: dict) -> None:
    oid = first_string(o, ORDER_KEYS)
    mail = first_string(o, TRACK_KEYS) or oid
    if not mail:
        return
    status = first_string(o, STATUS_KEYS)
    if any(b in status for b in BAD):
        return
    if any(k in status for k in ("签收", "已完成", "交易成功", "待评价", "已评价")):
        state = 3
    elif any(k in status for k in ("派送", "配送", "派件")):
        state = 5
    elif any(k in status for k in ("待发货", "未发货", "待成团")):
        state = 1
    else:
        state = 0
    key = oid or mail
    out[key] = {
        "id": key, "mailNo": mail, "companyCode": "PDD",
        "companyName": first_string(o, COMPANY_KEYS) or "拼多多快递",
        "latestText": first_string_deep(o, TEXT_KEYS, 4) or status.strip(),
        "latestTime": time_string(first_value_deep(o, TIME_KEYS, 4)),
        "state": state, "stateName": status.strip()[:60], "stateNum": {3: 107, 5: 105, 1: 103}.get(state, 105),
        "provider": "Pinduoduo", "queryChannel": key, "source": "pdd",
        "accountId": account["id"], "accountLabel": account.get("label", ""),
    }
    g = _goods(o)
    if g:
        goods[mail] = g


def _collect(node, out: dict, goods: dict, account: dict) -> None:
    if isinstance(node, dict):
        if _looks_like_order(node):
            _parse_order(node, out, goods, account)
            return
        for v in node.values():
            if isinstance(v, (dict, list)):
                _collect(v, out, goods, account)
    elif isinstance(node, list):
        for v in node:
            if isinstance(v, (dict, list)):
                _collect(v, out, goods, account)


def _convert(caps: list[tuple[str, str]], account: dict) -> tuple[dict, dict]:
    out, goods = {}, {}
    for url, body in caps:
        if "track/status" in url or not _relevant(url, body):
            continue
        try:
            root = json.loads(body)
        except ValueError:
            continue
        _collect(root, out, goods, account)
    return out, goods


def _empty_authenticated(caps: list[tuple[str, str]]) -> bool:
    for url, body in caps:
        if "/aristotle/order_list" not in url:
            continue
        try:
            root = json.loads(body)
        except ValueError:
            continue
        if root.get("error_code", 0) or root.get("errorCode", 0) or root.get("success") is False:
            continue
        orders = root.get("orders") if "orders" in root else (root.get("data") or {}).get("orders")
        if isinstance(orders, list) and not orders:
            return True
    return False


def _hook(page: Page, caps: list, tasks: list) -> None:
    async def grab(resp):
        try:
            if "proxy/api" in resp.url:
                body = await resp.text()
                if body and _relevant(resp.url, body):
                    caps.append((resp.url, body))
        except Exception:  # noqa: BLE001 - 页面跳转后读取失败可忽略
            pass

    page.on("response", lambda r: tasks.append(asyncio.ensure_future(grab(r))))


async def fetch(bm: BrowserManager, account: dict, profile: Path) -> tuple[list[dict], dict]:
    ctx = await bm.context(profile)
    caps: list[tuple[str, str]] = []
    tasks: list[asyncio.Task] = []
    try:
        page = await ctx.new_page()
        _hook(page, caps, tasks)
        await page.goto(ORDER_PAGE, wait_until="domcontentloaded", timeout=45000)
        await asyncio.sleep(4)
        if _is_login(page.url):
            raise RuntimeError("拼多多登录已失效，请在服务器网页里重新登录拼多多")
        last, stable = -1, 0
        for rnd in range(1, 19):
            orders, _ = _convert(caps, account)
            if rnd >= 3 and not orders and _empty_authenticated(caps):
                break
            log.info("pdd round=%d orders=%d caps=%d", rnd, len(orders), len(caps))
            if rnd in (2, 5):
                await click_text(page, ["全部", "全部订单"])
            await click_text(page, ["加载更多", "查看更多", "下一页"])
            await swipe_up(page)
            if len(orders) == last:
                stable += 1
            else:
                stable, last = 0, len(orders)
            if orders and stable >= 3 and rnd >= 6:
                break
            await asyncio.sleep(2.5)
        await asyncio.gather(*tasks, return_exceptions=True)
        await ctx.storage_state(path=str(profile))
        orders, goods = _convert(caps, account)
        return list(orders.values()), goods
    finally:
        await ctx.close()


# ───────── 物流页轨迹（DOM 文本解析，移植 PddTraceFetcher.parseDomPoints） ─────────

_DATE = re.compile(r"(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?)")
_NOISE = {"复制", "展开", "物流服务", "在App打开", "顶部", "打赏快递员", "拨打电话", "货物跟踪", "包裹追踪"}
_REC = re.compile(r"(即将恢复|本店已拼|全店总售|已抢|券后|立减|未发货秒退|24小时发货|限1件|退货包运费|\d+\.\d+$)")
_SKIP = re.compile(r"(订单编号|收货地址|快递员|网点电话|投诉电话|联系电话|微信公众号|^：$|^\d+$)")
_KW = ("快件", "包裹", "派件", "揽收", "签收", "发往", "到达", "转运", "投递", "取件", "出库", "配送", "妥投", "物流", "运输",
       "发出", "已投", "送达", "查询", "无轨迹", "拦截", "快递", "签收人", "配送至", "快递员", "网点")
_CONT = ("快递", "签收", "物流", "包裹", "快件", "网点", "电话", "服务", "反馈", "感谢", "送达", "配送", "运输", "预计")


def parse_dom_points(text: str) -> list[dict]:
    points: list[dict] = []
    pending: str | None = None
    ptime = ""
    in_rec = False

    def flush():
        d = (pending or "").strip()
        if len(d) >= 4 and ptime:
            points.append({"time": ptime, "context": d[:400]})

    for raw in text.split("\n"):
        line = raw.strip()
        if not line:
            continue
        m = _DATE.search(line)
        if m:
            flush()
            ptime = m.group(1)
            in_rec = False
            prefix = line[:m.start()].strip()
            pending = prefix if prefix and prefix not in _NOISE else ""
            continue
        if line in _NOISE or in_rec:
            continue
        if len(line) >= 6 and any(k in line for k in _KW):
            pending = ((pending or "") + " " + line) if len(pending or "") < 400 else pending
            continue
        if _REC.search(line):
            flush()
            pending, in_rec = None, True
            continue
        if _SKIP.search(line):
            continue
        if pending is None:
            if len(line) >= 4 and not line.isdigit():
                pending = line
        elif any(k in line for k in _CONT) and len(pending) < 400:
            pending += " " + line
    flush()
    return sorted(points, key=lambda p: p["time"])


async def fetch_trace(page: Page, item: dict) -> list[dict]:
    order_id = item.get("queryChannel") or item["mailNo"]
    await page.goto(TRACE_PAGE + order_id, wait_until="domcontentloaded", timeout=30000)
    await asyncio.sleep(2.5)
    if _is_login(page.url):
        raise RuntimeError("拼多多登录已失效")
    last = -1
    points: list[dict] = []
    for rnd in range(1, 11):
        if "order.html" in page.url or "orders.html" in page.url:
            await click_text(page, ["查看物流", "查物流", "物流详情", "物流信息"], max_len=12, contains="物流")
            await asyncio.sleep(1.5)
        text = await page.evaluate("() => document.body ? document.body.innerText.slice(0, 30000) : ''")
        points = parse_dom_points(text)
        if points and len(points) == last and rnd >= 2:
            break
        last = len(points)
        await asyncio.sleep(2)
    return points


def label_from_cookies(cookies: dict[str, str]) -> tuple[str, str]:
    uid = cookies.get("pdd_user_id", "")
    return uid, f"拼多多 {uid[-4:]}" if uid else "拼多多账号"


def logged_in(cookies: dict[str, str]) -> bool:
    return bool(cookies.get("PDDAccessToken"))


def prompt_only(item: dict) -> bool:
    return item.get("latestText", "") in PROMPTS
