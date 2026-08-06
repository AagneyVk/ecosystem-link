from __future__ import annotations

import asyncio
import enum
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional


class JobState(str, enum.Enum):
    CREATED = "created"
    ACCEPTED = "accepted"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


_ALLOWED = {
    JobState.CREATED: {JobState.ACCEPTED, JobState.RUNNING, JobState.CANCELLED, JobState.FAILED},
    JobState.ACCEPTED: {JobState.RUNNING, JobState.CANCELLED, JobState.FAILED},
    JobState.RUNNING: {JobState.COMPLETED, JobState.CANCELLED, JobState.FAILED},
}


@dataclass
class Job:
    device_id: str
    capability: str
    operation: str
    correlation_id: str
    job_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    state: JobState = JobState.CREATED
    progress: float = 0.0
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    result: dict[str, Any] = field(default_factory=dict)
    error: Optional[dict[str, Any]] = None

    def to_dict(self) -> dict[str, Any]:
        return {"job_id": self.job_id, "correlation_id": self.correlation_id,
                "device_id": self.device_id, "timestamp": self.updated_at,
                "capability": self.capability, "operation": self.operation,
                "state": self.state.value, "progress": self.progress,
                "result": self.result, "error": self.error}


class JobManager:
    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._lock = asyncio.Lock()

    async def create(self, device_id: str, capability: str, operation: str,
                     correlation_id: str | None = None) -> Job:
        job = Job(device_id=device_id, capability=capability, operation=operation,
                  correlation_id=correlation_id or str(uuid.uuid4()))
        async with self._lock:
            self._jobs[job.job_id] = job
        return job

    async def transition(self, job_id: str, state: JobState, *, progress: float | None = None,
                         result: dict | None = None, error: dict | None = None) -> Job:
        async with self._lock:
            job = self._jobs[job_id]
            if state != job.state and state not in _ALLOWED.get(job.state, set()):
                raise ValueError(f"invalid job transition {job.state.value} -> {state.value}")
            job.state = state
            if progress is not None:
                job.progress = max(0.0, min(1.0, float(progress)))
            if state == JobState.COMPLETED:
                job.progress = 1.0
            if result is not None:
                job.result = result
            if error is not None:
                job.error = error
            job.updated_at = time.time()
            return job

    def all(self, device_id: str | None = None) -> list[Job]:
        jobs = list(self._jobs.values())
        return [j for j in jobs if not device_id or j.device_id == device_id]
