# apps/trigger/tasks.py
# coding=utf-8
from __future__ import annotations

# 作为 Celery autodiscover 的入口，确保任务模块被导入从而完成注册
from trigger.handler.impl.trigger.scheduled_trigger import deploy_scheduled_trigger  # noqa: F401
