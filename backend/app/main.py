"""
app/main.py — FastAPI application factory, lifespan, middleware, and router mounting.
"""
from __future__ import annotations

from contextlib import asynccontextmanager
import logging
from typing import Any, AsyncIterator, Dict

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.auth import require_active_student
from app.core.config import get_settings
from app.core.errors import TraceIdMiddleware, register_exception_handlers
from app.core.firebase import get_firestore, init_firebase
from app.routers import account, events, health, media
from app.worker.runner import WorkerLoop, recover_pending_on_boot


@asynccontextmanager
async def lifespan(application: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    logging.basicConfig(
        level=getattr(logging, settings.log_level, logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    init_firebase()
    worker_loop: WorkerLoop | None = None
    if settings.app_env != "test":
        db = get_firestore()
        if settings.run_boot_recovery:
            await recover_pending_on_boot(db)
        worker_loop = WorkerLoop(db)
        worker_loop.start()
        application.state.worker_loop = worker_loop
    try:
        yield
    finally:
        if worker_loop is not None:
            await worker_loop.stop()
            application.state.worker_loop = None


def create_app() -> FastAPI:
    """Create and configure the LostLink FastAPI application."""
    settings = get_settings()

    application = FastAPI(
        title="LostLink Backend",
        version="0.1.0",
        lifespan=lifespan,
    )

    application.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins_list,
        allow_credentials=False,
        allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
        allow_headers=[
            "Authorization",
            "Content-Type",
            "X-Trace-Id",
            "X-Backend-Api-Key",
        ],
        expose_headers=["X-Trace-Id"],
    )
    application.add_middleware(TraceIdMiddleware)
    register_exception_handlers(application)

    application.include_router(health.router)
    application.include_router(events.router)
    application.include_router(media.router)
    application.include_router(account.router)

    @application.get("/api/v1/session")
    async def session(decoded: Dict[str, Any] = Depends(require_active_student)) -> Dict[str, str]:
        return {"uid": decoded["uid"]}

    return application


app = create_app()
