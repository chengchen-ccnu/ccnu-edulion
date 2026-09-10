"""ASGI compatibility entry point; prefer ``python -m edu_agent_app`` for local runs."""

from edu_agent_app.api import app
from edu_agent_app.__main__ import main


if __name__ == "__main__":
    main()
