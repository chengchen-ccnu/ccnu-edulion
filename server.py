"""Compatibility entry point; prefer ``python -m edu_agent_app`` for local runs."""

from edu_agent_app.__main__ import app, main


if __name__ == "__main__":
    main()
