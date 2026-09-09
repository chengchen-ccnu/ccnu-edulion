"""在线 Agent 与独立 Index Worker 的命令行入口。"""

import asyncio
import sys


def main() -> None:
    """无子命令时保持旧 HTTP 入口；index-worker 完全独立启动，不加载在线 Agent。"""
    if len(sys.argv) > 1 and sys.argv[1] == "index-worker":
        from .indexing.cli import run_index_worker

        raise SystemExit(run_index_worker(sys.argv[2:]))
    import uvicorn

    from .api import app

    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    uvicorn.run(app, host="0.0.0.0", port=8000)


if __name__ == "__main__":
    main()
