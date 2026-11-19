import asyncio
from contextlib import asynccontextmanager
import logging
from fastapi import FastAPI, HTTPException
from app.config import settings
from app.consumer import start_consumer
from app.db import valuations_col
from app.outbox_publisher import run_outbox_publisher

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    rmq_conn = await start_consumer()
    outbox_task = asyncio.create_task(run_outbox_publisher())
    try:
        yield
    finally:
        outbox_task.cancel()
        await asyncio.gather(outbox_task, return_exceptions=True)
        await rmq_conn.close()


app = FastAPI(title=settings.app_name, lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "UP", "service": settings.app_name}


@app.get("/api/v1/valuations/{claim_id}")
async def get_valuation(claim_id: str):
    doc = await valuations_col.find_one({"_id": claim_id})
    if not doc:
        raise HTTPException(status_code=404, detail=f"Valuation not found for claim {claim_id}")
    return doc