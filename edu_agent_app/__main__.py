"""在线 Agent 与独立 Index Worker 的命令行入口。"""

import asyncio
import sys


def main() -> None:
    """无子命令时启动 T13.3 HTTP；index-worker 仍完全独立且不加载在线模型。"""
    if len(sys.argv) > 1 and sys.argv[1] == "index-worker":
        from .indexing.cli import run_index_worker

        raise SystemExit(run_index_worker(sys.argv[2:]))
    import uvicorn

    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    uvicorn.run("edu_agent_app.api:app", host="0.0.0.0", port=8000)


if __name__ == "__main__":
    main()
