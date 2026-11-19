import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from app.config import settings
from app.consumer import start_consumer
from app.db import risk_scores_col
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


@app.get("/api/v1/risk-scores/{claim_id}")
async def get_risk_score(claim_id: str):
    doc = await risk_scores_col.find_one({"_id": claim_id})
    if not doc:
        raise HTTPException(status_code=404, detail=f"Risk score not found for claim {claim_id}")
    return doc