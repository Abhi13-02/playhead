-- scheduled_event: the tentpole calendar (FR-7). One row per upcoming premiere or live match.
--
-- Unrelated to playback_events despite the shared word: that table is an append log of what
-- viewers did, this one is a short forward-looking list of what is about to happen. It is read by
-- the scaling controller, not by any product endpoint.
--
-- It lives in Postgres rather than in memory because the app is replicated from this phase on.
-- In-memory state would give every replica its own private calendar, so which replica answered
-- would decide what the controller saw.
create table scheduled_event (
    id                bigint      generated always as identity primary key,
    name              text        not null,
    starts_at         timestamptz not null,
    expected_peak_rps integer     not null,
    title_ids         text        not null,
    created_at        timestamptz not null default now()
);

-- The controller's only query: "what starts soon?" — a range scan over starts_at, ascending.
create index idx_scheduled_event_starts_at on scheduled_event (starts_at);
