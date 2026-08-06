import pytest
from ecosystem_hub.core.job_manager import JobManager, JobState


@pytest.mark.asyncio
async def test_job_lifecycle_and_progress_clamping():
    manager = JobManager()
    job = await manager.create("phone", "camera.snapshot", "capture", "corr")
    await manager.transition(job.job_id, JobState.RUNNING, progress=2)
    assert job.progress == 1
    await manager.transition(job.job_id, JobState.COMPLETED, result={"file_id": "x"})
    assert job.to_dict()["correlation_id"] == "corr"


@pytest.mark.asyncio
async def test_invalid_terminal_transition_is_rejected():
    manager = JobManager()
    job = await manager.create("phone", "camera.snapshot", "capture")
    await manager.transition(job.job_id, JobState.CANCELLED)
    with pytest.raises(ValueError):
        await manager.transition(job.job_id, JobState.RUNNING)
