-- playback_state: the folded current position for one (profile, title). Every product read hits
-- this table. Exactly one row per pair, replaced in place by the fold-consumer's upsert.
create table playback_state (
    profile_id       text        not null,
    title_id         text        not null,
    position_seconds integer     not null,
    duration_seconds integer     not null,
    sequence         bigint      not null,
    updated_at       timestamptz not null default now(),
    primary key (profile_id, title_id)
);

-- Serves continue-watching: WHERE profile_id = ? ORDER BY updated_at DESC LIMIT n.
-- profile_id leads (equality match -> contiguous slice); updated_at DESC second so the slice is
-- already in the wanted order and no sort step is needed. INCLUDE carries the projected columns
-- so the query is answered from the index alone (index-only scan).
create index idx_continue_watching
    on playback_state (profile_id, updated_at desc)
    include (position_seconds, duration_seconds);

-- playback_events: immutable append log of every heartbeat. Range-partitioned by month on
-- received_at (server clock) so old data drops with a single DROP TABLE and time-range scans
-- only touch the relevant months. The partition key must be part of the primary key, hence
-- (id, received_at).
create table playback_events (
    id               bigint      generated always as identity,
    profile_id       text        not null,
    title_id         text        not null,
    device_id        text        not null,
    position_seconds integer     not null,
    duration_seconds integer     not null,
    client_timestamp bigint      not null,
    sequence         bigint      not null,
    received_at      timestamptz not null default now(),
    primary key (id, received_at)
) partition by range (received_at);

-- Partitions are created explicitly. An insert whose received_at falls outside every partition
-- fails, so a real deployment would create these ahead of time (a scheduled job or pg_partman).
create table playback_events_2026_09 partition of playback_events
    for values from ('2026-09-01') to ('2026-10-01');

create table playback_events_2026_10 partition of playback_events
    for values from ('2026-10-01') to ('2026-11-01');
