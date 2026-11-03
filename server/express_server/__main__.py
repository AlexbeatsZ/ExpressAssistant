"""启动：uv run express-server [--host 0.0.0.0] [--port 8765] [--data ./data]"""

from __future__ import annotations

import argparse
import asyncio
import logging
import socket
from pathlib import Path

from aiohttp import web

from . import __version__
from .api import Api
from .browser import BrowserManager
from .login import LoginManager
from .store import Store
from .sync import SyncEngine, scheduler


def _lan_ips() -> list[str]:
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(i for i in ips if not i.startswith("127."))


async def _run(args: argparse.Namespace) -> None:
    store = Store(Path(args.data).resolve())
    if args.token:
        store.config["token"] = args.token
        store.save_config()
    bm = BrowserManager(channel=args.browser or None, headless=not args.headed)
    engine = SyncEngine(store, bm)
    api = Api(store, engine, LoginManager(bm, store))
    runner = web.AppRunner(api.app(), access_log=None)
    await runner.setup()
    await web.TCPSite(runner, args.host, args.port).start()
    print(f"快递助手服务器 v{__version__} 已启动：http://{args.host}:{args.port}/")
    for ip in _lan_ips():
        print(f"  局域网访问：http://{ip}:{args.port}/")
    print(f"  访问令牌：{store.config['token']}")
    print(f"  MCP：POST http://<地址>:{args.port}/mcp  （Authorization: Bearer <令牌>）")
    print(f"  数据目录：{store.dir}")
    task = asyncio.create_task(scheduler(engine))
    try:
        await asyncio.Event().wait()
    finally:
        task.cancel()
        await runner.cleanup()
        await bm.close()


def main() -> None:
    p = argparse.ArgumentParser(prog="express-server", description="快递助手 PC 服务器")
    p.add_argument("--host", default="0.0.0.0", help="监听地址，默认 0.0.0.0（局域网可访问）；只给本机用填 127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--data", default=str(Path(__file__).resolve().parent.parent / "data"), help="数据目录")
    p.add_argument("--token", default="", help="指定访问令牌（默认首次启动随机生成并保存）")
    p.add_argument("--browser", default="", help="浏览器通道：msedge / chrome；默认依次尝试 Edge、Chrome、Playwright Chromium")
    p.add_argument("--headed", action="store_true", help="显示浏览器窗口（调试用）")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        asyncio.run(_run(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
