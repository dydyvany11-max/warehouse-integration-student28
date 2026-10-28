CREATE TABLE IF NOT EXISTS sync_runs (
    id UUID PRIMARY KEY,
    student_id INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    fetched_count INTEGER,
    total_count INTEGER,
    status TEXT NOT NULL DEFAULT 'RUNNING',
    server_result JSONB,
    error TEXT
);
CREATE TABLE IF NOT EXISTS spares (
    student_id INTEGER NOT NULL,
    spare_code TEXT NOT NULL,
    spare_name TEXT NOT NULL,
    spare_description TEXT NOT NULL,
    spare_type TEXT NOT NULL,
    spare_status TEXT NOT NULL,
    price TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    updated_at TEXT NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_present BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (student_id, spare_code)
);
CREATE TABLE IF NOT EXISTS spare_snapshots (
    run_id UUID NOT NULL REFERENCES sync_runs(id),
    student_id INTEGER NOT NULL,
    spare_code TEXT NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY (run_id, spare_code)
);
CREATE INDEX IF NOT EXISTS sync_runs_student_started ON sync_runs(student_id, started_at);
