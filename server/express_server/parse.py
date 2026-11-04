"""与 App 一致的文本解析：取件码、预计送达、分区、拼多多候选键容错取值。"""

from __future__ import annotations

import re
import time
from datetime import datetime
from typing import Any, Iterable

# 与 App GoodsPresentation.pickupRe 一致，另补「取件密码」
PICKUP_RE = re.compile(r"(取件码|取货码|提货码|自提码|驿站码|取件验证码|取件密码)[:：\s]*([A-Za-z0-9][A-Za-z0-9\-]{1,12})")
# 已到驿站/快递柜但轨迹里未必带码：也归入「待取件」
PICKUP_HINT_RE = re.compile(r"(待取件|已到达.{0,12}(驿站|快递柜|自提|代收点)|已放入|已存放|已暂存|丰巢|菜鸟驿站|兔喜|妈妈驿站|请凭|取件码|取货码|提货码)")


def pickup_code_from(text: str | None) -> str:
    if not text:
        return ""
    m = PICKUP_RE.search(text)
    return m.group(2) if m else ""


def pickup_code_from_points(points: Iterable[dict]) -> str:
    """轨迹按时间正序；从最新一条往前找。"""
    for p in reversed(list(points)):
        code = pickup_code_from(p.get("context"))
        if code:
            return code
    return ""


_DELIVERY = re.compile(r"(?:预计)?[【\[]?\s*(\d{1,2})月(\d{1,2})日\s*[】\]]?\s*(?:[（(]?周[^)）]*[)）])?\s*(送达|到达|派送)")
_FULL = re.compile(r"(?:预计)?[【\[]?\s*(\d{1,2})月(\d{1,2})日\s*[】\]]?\s*(?:[（(]?周[^)）]*[)）])?\s*(前发货|发货|送达|到达|派送)?")
_SIMPLE = re.compile(r"(\d{1,2})月(\d{1,2})日(?:[（(]周[^)）]*[)）])?(前发货|发货|送达|到达|派送)")
_TODAY = re.compile(r"预计(今天|明天|后天)(送达|到达|派送)")
_PLAIN = re.compile(r"预计\s*(\d{1,2})月(\d{1,2})日")


def eta_from(*texts: str | None) -> str:
    """移植自 App EtaParser.extract。"""
    joined = " ".join(t for t in texts if t)
    if not joined.strip():
        return ""
    m = _DELIVERY.search(joined)
    if m:
        return f"{m.group(1)}月{m.group(2)}日{m.group(3)}"
    for p in (_FULL, _SIMPLE, _TODAY):
        m = p.search(joined)
        if m:
            if p is _TODAY:
                return f"{m.group(1)}{m.group(2)}"
            return f"{m.group(1)}月{m.group(2)}日{m.group(3) or ''}"
    m = _PLAIN.search(joined)
    return f"{m.group(1)}月{m.group(2)}日" if m else ""


def section_of(item: dict) -> str:
    """移植自 App sectionKeyOf，并在最前面加「待取件」。"""
    if is_done(item):
        return "done"
    state, num = item.get("state", -1), item.get("stateNum", 0)
    if not is_done(item) and state != 4 and num not in range(108, 112):
        if item.get("pickupCode") or PICKUP_HINT_RE.search(item.get("latestText") or ""):
            return "pickup"
    if num in (105, 106) or state == 5:
        return "delivering"
    if num in (106, 107) or state == 3:
        return "done"
    if num in range(108, 112) or state == 4:
        return "abnormal"
    if num in (101, 103) or state == 1:
        return "notshipped"
    if state < 0 and num == 0:
        return "unknown"
    return "shipped"


SECTION_LABELS = {
    "pickup": "待取件",
    "delivering": "派送中",
    "shipped": "运输中",
    "notshipped": "未发货",
    "done": "已完成",
    "abnormal": "异常",
    "unknown": "待更新",
}


def is_done(item: dict) -> bool:
    if item.get("state") == 3 or item.get("stateNum") == 107 or item.get("receiptConfirmed") is True:
        return True
    if item.get("source") == "jd" and item.get("stateName") in ("处理成功", "已完成", "交易完成", "已收货"):
        return True
    if item.get("source") == "pdd":
        if (item.get("stateName") or "").startswith(PDD_COMPLETED):
            return True
        # 订单列表有时只给「拼过 N 次」等营销文案；以明确的最新取件终态纠正分区。
        text = item.get("latestText") or ""
        if any(w in text for w in ("已取走", "已完成取件", "快件领取成功")):
            return True
        if text.startswith("已签收") and not re.search(r"驿站|快递柜|代收|待取件", text):
            return True
    return False


PDD_COMPLETED = ("已签收", "已收货", "已提货", "已取货", "已取走", "交易成功", "已完成", "待评价", "已评价")


def state_label(item: dict) -> str:
    if is_done(item) and item.get("state") != 3 and item.get("stateNum") != 107:
        return "已签收"
    if item.get("stateName"):
        return item["stateName"]
    return {0: "运输中", 1: "已揽件", 3: "已签收", 4: "已拒签", 5: "派送中"}.get(item.get("state", -1), "未知")


def now_ms() -> int:
    return int(time.time() * 1000)


def fmt_millis(raw: int) -> str:
    ms = raw if raw > 1_000_000_000_000 else raw * 1000
    try:
        return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M")
    except (OverflowError, OSError, ValueError):
        return str(raw)


def time_string(v: Any) -> str:
    if v is None:
        return ""
    if isinstance(v, bool):
        return ""
    if isinstance(v, (int, float)):
        return fmt_millis(int(v))
    if isinstance(v, str):
        t = v.strip()
        if re.fullmatch(r"\d{10,13}", t):
            return fmt_millis(int(t))
        return t[:30]
    return ""


# ───────── 拼多多候选键容错取值（移植自 PddCapture） ─────────

def _scalar(v: Any) -> str:
    if isinstance(v, bool):
        return ""
    if isinstance(v, str) and v.strip():
        return v.strip()
    if isinstance(v, (int, float)):
        return str(v)
    return ""


def first_string(obj: dict, keys: Iterable[str]) -> str:
    for k in keys:
        if k not in obj:
            continue
        v = obj[k]
        s = _scalar(v)
        if s:
            return s
        if isinstance(v, dict):
            s = first_string(v, keys)
            if s:
                return s
    return ""


def first_string_deep(obj: dict, keys: Iterable[str], depth: int) -> str:
    v = first_value_deep(obj, keys, depth, numbers=False)
    return v.strip() if isinstance(v, str) else ""


def first_value_deep(obj: dict, keys: Iterable[str], depth: int, numbers: bool = True) -> Any:
    if depth <= 0:
        return None
    keys = tuple(keys)
    for k in keys:
        if k not in obj:
            continue
        v = obj[k]
        if isinstance(v, str) and v.strip():
            return v
        if numbers and isinstance(v, (int, float)) and not isinstance(v, bool):
            return v
        for child in _children(v):
            r = first_value_deep(child, keys, depth - 1, numbers)
            if r is not None:
                return r
    for v in obj.values():
        for child in _children(v):
            r = first_value_deep(child, keys, depth - 1, numbers)
            if r is not None:
                return r
    return None


def _children(v: Any) -> list[dict]:
    if isinstance(v, dict):
        return [v]
    if isinstance(v, list):
        return [e for e in v if isinstance(e, dict)]
    return []


def cookie_header(cookies: list[dict], *domains: str) -> str:
    """Playwright storage_state cookies → Cookie 头（按域后缀过滤，同名取第一个）。"""
    seen: dict[str, str] = {}
    for c in cookies:
        d = c.get("domain", "").lstrip(".")
        if domains and not any(d == x or d.endswith("." + x) for x in domains):
            continue
        seen.setdefault(c["name"], c["value"])
    return "; ".join(f"{k}={v}" for k, v in seen.items())


def cookie_map(cookies: list[dict], *domains: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for part in cookie_header(cookies, *domains).split("; "):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k] = v
    return out
