"""Playwright 浏览器管理：一个无头浏览器进程，按账号用独立 context（storage_state 文件）隔离会话。

与 App 隐藏 WebView 的思路一致：让页面自己发带签名的请求（京东 h5st / 拼多多 anti_content），
这里在浏览器内部读取响应（page.on("response")），不在网络层转发。
"""

from __future__ import annotations

import asyncio
import logging
import os
import sys
from pathlib import Path
from typing import Any

from playwright.async_api import Browser, BrowserContext, Page, Playwright, async_playwright

log = logging.getLogger("browser")

MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; 23127PN0CC) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
)
DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)
MOBILE_VIEWPORT = {"width": 400, "height": 860}

# 应用深链会让页面跳走：拦掉，留在 H5 页
_BLOCK_SCHEMES = ("pinduoduo://", "openapp.jdmobile://", "taobao://", "tbopen://", "intent://")


def desktop_available() -> bool:
    """只有当前进程能访问用户桌面时，才默认提供本机窗口登录。"""
    if sys.platform == "darwin":
        try:
            return os.getuid() != 0 and os.stat("/dev/console").st_uid == os.getuid()
        except OSError:
            return False
    if sys.platform == "win32":
        import ctypes
        from ctypes import wintypes

        class USEROBJECTFLAGS(ctypes.Structure):
            _fields_ = [("inherit", wintypes.BOOL), ("reserved", wintypes.BOOL), ("flags", wintypes.DWORD)]

        user32 = ctypes.windll.user32
        user32.GetProcessWindowStation.restype = wintypes.HANDLE
        user32.GetUserObjectInformationW.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p,
                                                    wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
        flags, needed = USEROBJECTFLAGS(), wintypes.DWORD()
        return bool(user32.GetUserObjectInformationW(user32.GetProcessWindowStation(), 1, ctypes.byref(flags),
                                                    ctypes.sizeof(flags), ctypes.byref(needed)) and flags.flags & 1)
    return bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


class BrowserManager:
    def __init__(self, channel: str | None = None, headless: bool = True):
        self.channel = channel
        self.headless = headless
        self._pw: Playwright | None = None
        self._browsers: dict[bool, Browser] = {}
        self._lock = asyncio.Lock()

    async def browser(self, headless: bool | None = None) -> Browser:
        hidden = self.headless if headless is None else headless
        async with self._lock:
            browser = self._browsers.get(hidden)
            if browser and browser.is_connected():
                return browser
            if self._pw is None:
                self._pw = await async_playwright().start()
            channels = [self.channel] if self.channel else ["msedge", "chrome", None]
            last: Exception | None = None
            for ch in channels:
                try:
                    kwargs: dict[str, Any] = {"headless": hidden, "args": ["--disable-blink-features=AutomationControlled"]}
                    if ch:
                        kwargs["channel"] = ch
                    browser = await self._pw.chromium.launch(**kwargs)
                    self._browsers[hidden] = browser
                    log.info("browser launched channel=%s headless=%s", ch or "bundled-chromium", hidden)
                    return browser
                except Exception as e:  # noqa: BLE001 - 依次尝试本机浏览器
                    last = e
            raise RuntimeError(
                "找不到可用的 Chromium 浏览器：请安装 Edge/Chrome，或运行 `uv run playwright install chromium`"
            ) from last

    async def context(self, profile: Path | None, mobile: bool = True, headless: bool | None = None) -> BrowserContext:
        b = await self.browser(headless)
        kwargs: dict[str, Any] = {"locale": "zh-CN", "timezone_id": "Asia/Shanghai"}
        if mobile:
            kwargs.update(user_agent=MOBILE_UA, viewport=MOBILE_VIEWPORT, device_scale_factor=2, is_mobile=True, has_touch=True)
        else:
            kwargs.update(user_agent=DESKTOP_UA, viewport={"width": 1100, "height": 760})
        if profile and profile.exists():
            kwargs["storage_state"] = str(profile)
        ctx = await b.new_context(**kwargs)
        await ctx.add_init_script("Object.defineProperty(navigator,'webdriver',{get:()=>undefined});")

        async def block(route):
            await route.abort()

        for scheme in _BLOCK_SCHEMES:
            await ctx.route(f"{scheme}**", block)
        return ctx

    async def close(self) -> None:
        for browser in self._browsers.values():
            await browser.close()
        self._browsers.clear()
        if self._pw:
            await self._pw.stop()
            self._pw = None


async def swipe_up(page: Page, steps: int = 8) -> None:
    """真实触摸滑动（拼多多虚拟列表靠 touch 事件触发增量加载；JS scrollTop 不够）。"""
    vp = page.viewport_size or MOBILE_VIEWPORT
    x = vp["width"] / 2
    y0, y1 = vp["height"] * 0.85, vp["height"] * 0.12
    cdp = await page.context.new_cdp_session(page)
    try:
        await cdp.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [{"x": x, "y": y0}]})
        for i in range(1, steps + 1):
            y = y0 + (y1 - y0) * i / steps
            await cdp.send("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": [{"x": x, "y": y}]})
            await asyncio.sleep(0.03)
        await cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
    finally:
        await cdp.detach()
    # 兜底：程序化滚动最大的滚动容器
    await page.evaluate(
        """() => { try {
            let best = document.scrollingElement || document.documentElement, bestSh = 0;
            for (const n of document.querySelectorAll('*')) {
              const sh = n.scrollHeight || 0;
              if (sh > bestSh && sh > n.clientHeight + 100 && n.clientHeight > 100) { best = n; bestSh = sh; }
            }
            best.scrollTop = best.scrollTop + Math.floor((best.clientHeight || 800) * 0.85);
            window.scrollBy(0, 800);
        } catch (e) {} }"""
    )


async def click_text(page: Page, texts: list[str], max_len: int = 0, contains: str = "") -> str:
    """点击第一个可见的文字元素（页面是 React/Taro，用 Playwright 真实点击而非合成 click）。"""
    box = await page.evaluate(
        """([texts, maxLen, contains]) => {
          let best = null;
          for (const e of document.querySelectorAll('div,span,button,a,li')) {
            const t = (e.innerText || '').trim();
            const r = e.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) continue;
            const hit = texts.includes(t) || (contains && t.includes(contains) && t.length <= maxLen);
            if (hit && (!best || r.height < best.r.height)) best = {e, r, t};
          }
          if (!best) return null;
          best.e.scrollIntoView({block: 'center'});
          const r = best.e.getBoundingClientRect();
          return {x: r.x + r.width / 2, y: r.y + r.height / 2, t: best.t};
        }""",
        [texts, max_len, contains],
    )
    if not box:
        return ""
    await page.mouse.click(box["x"], box["y"])
    return box["t"]
