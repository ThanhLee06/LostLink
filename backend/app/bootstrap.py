"""Core server entrypoint: uvicorn app.bootstrap:app. Feature APIs live in app.main."""
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from fastapi import Depends, FastAPI

from app.core.auth import require_active_student
from app.core.errors import TraceIdMiddleware, register_exception_handlers
from app.core.firebase import init_firebase
from app.routers import health


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    init_firebase()
    yield


def create_app() -> FastAPI:
    application = FastAPI(title="LostLink Core API", version="0.1.0", lifespan=lifespan)
    application.add_middleware(TraceIdMiddleware)
    register_exception_handlers(application)
    application.include_router(health.router)

    @application.get("/api/v1/session")
    async def session(decoded: dict[str, Any] = Depends(require_active_student)) -> dict[str, str]:
        return {"uid": decoded["uid"]}

    return application


app = create_app()
