"""登录：桌面本机使用真实浏览器窗口；无桌面或远程访问时使用远程画面或导入 Cookie。

适用于没有显示器的服务器（如 Windows 服务会话）；手机浏览器也能完成扫码、短信验证码和滑块。
登录态以 Playwright storage_state 保存为 profiles/<channel>-<账号id>.json。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from typing import Any

from playwright.async_api import BrowserContext, Error as PlaywrightError, Page

from .browser import BrowserManager, desktop_available
from .channels import jd, pdd, taobao, xiaomi
from .parse import cookie_map, now_ms
from .store import Store

log = logging.getLogger("login")

IDLE_TIMEOUT = 15 * 60

# 导入 Cookie 请求头（没有域信息）时写到哪些域；也用于过滤 JSON 导出里的无关域
COOKIE_DOMAINS = {
    "jd": ("jd.com",),
    "taobao": ("taobao.com", "tmall.com"),
    "pdd": ("yangkeduo.com", "pinduoduo.com"),
    "xiaomi": ("xiaomi.com",),
}
_SAME_SITE = {"no_restriction": "None", "none": "None", "lax": "Lax", "strict": "Strict"}


def parse_cookie_text(text: str, domains: tuple[str, ...]) -> list[dict]:
    """支持三种粘贴格式：Cookie 请求头字符串、Cookie-Editor/EditThisCookie 的 JSON 数组、Playwright storage_state。"""
    text = text.strip()
    if text.startswith("{") or text.startswith("["):
        data = json.loads(text)
        raw = data.get("cookies", []) if isinstance(data, dict) else data
        if not isinstance(raw, list):
            raise ValueError("Cookie JSON 必须是数组，或包含 cookies 数组的 storage_state")
        out = []
        for c in raw:
            if not isinstance(c, dict) or not c.get("name"):
                continue
            domain = str(c.get("domain") or "." + domains[0])
            if not any(domain.lstrip(".") == d or domain.lstrip(".").endswith("." + d) for d in domains):
                continue
            expires = c.get("expires", c.get("expirationDate"))
            out.append({
                "name": c["name"], "value": str(c.get("value", "")), "domain": domain, "path": c.get("path") or "/",
                "expires": float(expires) if expires and not c.get("session") else -1,
                "httpOnly": bool(c.get("httpOnly")), "secure": bool(c.get("secure")),
                "sameSite": _SAME_SITE.get(str(c.get("sameSite", "lax")).lower(), "Lax"),
            })
        return out
    if text.lower().startswith("cookie:"):
        text = text[7:]
    pairs = []
    for part in text.replace("\n", ";").split(";"):
        if "=" in part:
            k, v = part.strip().split("=", 1)
            if k:
                pairs.append((k.strip(), v.strip()))
    return [{"name": k, "value": v, "domain": "." + d, "path": "/", "expires": -1,
             "httpOnly": False, "secure": True, "sameSite": "None"} for d in domains for k, v in pairs]


class LoginSession:
    def __init__(self, channel: str, ctx: BrowserContext, page: Page, mobile: bool, mode: str = "remote"):
        self.id = uuid.uuid4().hex[:12]
        self.channel = channel
        self.ctx = ctx
        self.page = page
        self.mobile = mobile
        self.mode = mode
        self.status = "waiting"
        self.message = "请在这台服务器打开的浏览器窗口里完成登录" if mode == "local" else "请在画面里完成登录"
        self.account: dict | None = None
        self.touched = time.time()
        self.urls: list[str] = []
        self.task: asyncio.Task | None = None

    def public(self) -> dict:
        return {
            "id": self.id, "channel": self.channel, "mode": self.mode, "status": self.status, "message": self.message,
            "url": self.page.url if not self.page.is_closed() else "",
            "viewport": self.page.viewport_size, "account": self.account,
        }


class LoginManager:
    def __init__(self, bm: BrowserManager, store: Store, login_mode: str = "auto"):
        self.bm = bm
        self.store = store
        self.login_mode = login_mode
        self.sessions: dict[str, LoginSession] = {}

    def options(self, local_client: bool) -> dict:
        available = desktop_available()
        mode = self.login_mode
        if mode == "auto":
            mode = "local" if available and local_client else "remote"
        return {"defaultMode": mode, "localAvailable": available}

    async def start(self, channel: str, mode: str = "remote") -> LoginSession:
        if mode not in ("local", "remote"):
            raise ValueError("登录方式必须是 local 或 remote")
        if mode == "local" and not desktop_available():
            raise ValueError("服务器当前没有可用桌面，请使用远程画面登录或导入 Cookie")
        await self.gc()
        mobile = channel != "xiaomi"
        ctx = await self.bm.context(None, mobile=mobile, headless=mode != "local")
        try:
            page = await ctx.new_page()
            s = LoginSession(channel, ctx, page, mobile, mode)
            page.on("framenavigated", lambda f: s.urls.append(f.url) if f == page.main_frame else None)
            url = {
                "jd": jd.LOGIN_URL,
                "taobao": "https://login.m.taobao.com/login.htm",
                "pdd": pdd.LOGIN_URL,
            }.get(channel) or await xiaomi.login_url()
            await page.goto(url, wait_until="domcontentloaded", timeout=45000)
            if mode == "local":
                await page.bring_to_front()
        except BaseException:
            await ctx.close()
            raise
        self.sessions[s.id] = s
        s.task = asyncio.create_task(self._watch(s))
        return s

    def get(self, sid: str) -> LoginSession | None:
        s = self.sessions.get(sid)
        if s:
            s.touched = time.time()
        return s

    async def close(self, sid: str) -> None:
        s = self.sessions.pop(sid, None)
        if s:
            if s.task and not s.task.done():
                s.task.cancel()
                await asyncio.gather(s.task, return_exceptions=True)
            await s.ctx.close()

    async def shutdown(self) -> None:
        for sid in list(self.sessions):
            await self.close(sid)

    async def gc(self) -> None:
        for sid, s in list(self.sessions.items()):
            if time.time() - s.touched > IDLE_TIMEOUT or s.status != "waiting" and time.time() - s.touched > 120:
                await self.close(sid)

    async def screenshot(self, s: LoginSession) -> bytes:
        return await s.page.screenshot(type="jpeg", quality=70, scale="css")

    async def input(self, s: LoginSession, ev: dict[str, Any]) -> None:
        p = s.page
        kind = ev.get("type")
        if kind == "click":
            x, y = float(ev["x"]), float(ev["y"])
            if s.mobile:
                await p.touchscreen.tap(x, y)
            else:
                await p.mouse.click(x, y)
        elif kind == "text":
            await p.keyboard.type(str(ev.get("text", "")), delay=40)
        elif kind == "key":
            await p.keyboard.press(str(ev.get("key", "Enter")))
        elif kind == "scroll":
            await p.mouse.wheel(0, float(ev.get("dy", 300)))
        elif kind == "drag":
            pts = ev.get("points") or []
            if len(pts) >= 2:
                await p.mouse.move(pts[0][0], pts[0][1])
                await p.mouse.down()
                for x, y in pts[1:]:
                    await p.mouse.move(x, y, steps=2)
                await p.mouse.up()
        elif kind == "back":
            await p.go_back()
        elif kind == "reload":
            await p.reload()

    # ───────── 登录成功检测 ─────────

    async def _watch(self, s: LoginSession) -> None:
        try:
            while s.status == "waiting":
                await asyncio.sleep(2)
                if s.page.is_closed() or time.time() - s.touched > IDLE_TIMEOUT:
                    s.status, s.message = "cancelled", "登录窗口已关闭或会话已超时，请重新登录"
                    await s.ctx.close()
                    return
                cookies = await s.ctx.cookies()
                done = await getattr(self, f"_check_{s.channel}")(s, cookies)
                if done:
                    s.status = "done"
                    if not s.message.startswith("登录成功"):
                        s.message = f"登录成功：{s.account['label']}"
                    log.info("login ok channel=%s label=%s", s.channel, s.account["label"])
                    await s.ctx.close()
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001 - 失败原因展示给网页
            log.exception("login watch fail")
            s.status, s.message = "error", f"登录失败：{e}"
            await s.ctx.close()

    async def _save_profile(self, s: LoginSession, key: str, label: str, extra: dict | None = None) -> None:
        s.account = self._save_state(s.channel, key, label, await s.ctx.storage_state(), extra)

    def _save_state(self, channel: str, key: str, label: str, state: dict, extra: dict | None = None) -> dict:
        acc = self.store.upsert_account(channel, key, label, {"loggedInAt": now_ms(), **(extra or {})})
        name = f"{channel}-{acc['id']}.json"
        self.store.profile_path(name).write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
        self.store.update_account(channel, acc["id"], payload={"profile": name}, lastError="")
        return self.store.public_account(channel, acc)

    # ───────── Cookie 导入（远程画面过不了人机验证时用） ─────────

    async def import_cookies(self, channel: str, text: str) -> dict:
        cookies = parse_cookie_text(text, COOKIE_DOMAINS[channel])
        if not cookies:
            raise ValueError("没有识别到 Cookie：请粘贴 Cookie 请求头（k=v; k2=v2）或 Cookie-Editor 导出的 JSON")
        m = cookie_map(cookies, *COOKIE_DOMAINS[channel])
        state = {"cookies": cookies, "origins": []}
        if channel == "jd":
            need = ("pt_key", "pt_pin")
            key, label = jd.label_from_cookies(m)
        elif channel == "taobao":
            need = ("cookie2", "unb")
            key, label = taobao.label_from_cookies(m)
        elif channel == "pdd":
            need = ("PDDAccessToken",)
            key, label = pdd.label_from_cookies(m)
        else:
            need = ("passToken", "userId", "deviceId")
        missing = [k for k in need if not m.get(k)]
        if missing:
            raise ValueError(f"缺少登录 Cookie：{'、'.join(missing)}（HttpOnly 的 Cookie 在 document.cookie 里看不到，请从开发者工具的请求头复制）")
        if channel != "xiaomi":
            return self._save_state(channel, key, label, state)
        cred = await xiaomi.mint(m["passToken"], m["userId"], m["deviceId"])
        existing = next((a for a in self.store.accounts("xiaomi") if a.get("key") == m["userId"]), None)
        ids = {} if existing and existing["payload"].get("oaid") else xiaomi.new_device_ids()
        return self._save_state("xiaomi", m["userId"], f"小米 {m['userId'][-4:]}", state, {
            **cred, **ids, "phones": (existing or {}).get("payload", {}).get("phones") or [],
            "passToken": m["passToken"], "userId": m["userId"], "deviceId": m["deviceId"],
        })

    async def _open_orders(self, s: LoginSession, url: str) -> None:
        try:
            await s.page.goto(url, wait_until="domcontentloaded", timeout=45000)
        except PlaywrightError as e:
            # 登录完成时平台可能同时触发跳转/唤起 App，导致我们这次导航被取消。
            # 凭证已在 Cookie 中，不应因 ERR_ABORTED 丢掉刚完成的登录。
            if s.page.is_closed() or "net::ERR_ABORTED" not in str(e):
                raise
            log.info("post-login navigation interrupted channel=%s", s.channel)
        await asyncio.sleep(3)

    async def _check_jd(self, s: LoginSession, cookies: list[dict]) -> bool:
        m = cookie_map(cookies, "jd.com")
        if not (m.get("pt_key") and m.get("pt_pin")):
            return False
        # 进一次订单中心，让 trade/api 域会话落地
        await self._open_orders(s, jd.ORDER_CENTER)
        key, label = jd.label_from_cookies(m)
        await self._save_profile(s, key, label)
        return True

    async def _check_taobao(self, s: LoginSession, cookies: list[dict]) -> bool:
        m = cookie_map(cookies, *taobao.DOMAINS)
        if not (m.get("cookie2") and (m.get("unb") or m.get("cookie1"))):
            return False
        await self._open_orders(s, "https://h5.m.taobao.com/mlapp/olist.html")
        key, label = taobao.label_from_cookies(cookie_map(await s.ctx.cookies(), *taobao.DOMAINS))
        await self._save_profile(s, key, label)
        return True

    async def _check_pdd(self, s: LoginSession, cookies: list[dict]) -> bool:
        m = cookie_map(cookies, "yangkeduo.com", "pinduoduo.com")
        if not pdd.logged_in(m):
            return False
        await self._open_orders(s, pdd.ORDER_PAGE)
        key, label = pdd.label_from_cookies(cookie_map(await s.ctx.cookies(), "yangkeduo.com", "pinduoduo.com"))
        await self._save_profile(s, key, label)
        return True

    async def _check_xiaomi(self, s: LoginSession, cookies: list[dict]) -> bool:
        m = cookie_map(cookies, "xiaomi.com")
        if not (m.get("passToken") and m.get("userId") and m.get("deviceId")):
            return False
        cred = await xiaomi.mint(m["passToken"], m["userId"], m["deviceId"])
        phone = next((p for p in map(xiaomi.phone_from_url, s.urls) if p), "")
        existing = next((a for a in self.store.accounts("xiaomi") if a.get("key") == m["userId"]), None)
        ids = {} if existing and existing["payload"].get("oaid") else xiaomi.new_device_ids()
        phones = (existing or {}).get("payload", {}).get("phones") or ([phone] if phone else [])
        await self._save_profile(s, m["userId"], f"小米 {m['userId'][-4:]}", {
            **cred, **ids, "phones": phones,
            "passToken": m["passToken"], "userId": m["userId"], "deviceId": m["deviceId"],
        })
        if not phones:
            s.message = "登录成功：请在账号设置里填写收件手机号（小米只按手机号查件）"
        return True
