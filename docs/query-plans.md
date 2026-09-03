# Query plans

Committed `EXPLAIN ANALYZE` output for the queries that matter. Regenerate with
`load/seed_playback_state.sql` (200k rows) plus one heavy profile `p_hot` (5,000 titles), then run
the statements below.

---

## Continue-watching (FR-4)

```sql
SELECT title_id, position_seconds, duration_seconds
FROM playback_state
WHERE profile_id = 'p_hot'
ORDER BY updated_at DESC
LIMIT 20;
```

`p_hot` has 5,000 rows; the table has ~205,000.

### With `idx_continue_watching` — index scan, no sort

```
Limit  (cost=0.42..17.44 rows=20 width=20) (actual time=0.026..0.067 rows=20 loops=1)
  ->  Index Scan using idx_continue_watching on playback_state
        (cost=0.42..4385.02 rows=5152 width=20) (actual time=0.025..0.064 rows=20 loops=1)
        Index Cond: (profile_id = 'p_hot'::text)
Planning Time: 0.288 ms
Execution Time: 0.088 ms
```

The index is ordered `(profile_id, updated_at DESC)`, so Postgres seeks to `p_hot`, walks 20
entries already in the wanted order, and stops. No `Sort` node. 20 rows touched.

### Without the index — PK scan + sort of the whole profile

```
Limit  (cost=2066.84..2066.89 rows=20 width=20) (actual time=1.435..1.437 rows=20 loops=1)
  ->  Sort  (cost=2066.84..2079.72 rows=5152 width=20) (actual time=1.433..1.435 rows=20 loops=1)
        Sort Key: updated_at DESC
        Sort Method: top-N heapsort  Memory: 27kB
        ->  Bitmap Heap Scan on playback_state
              (cost=156.35..1929.75 rows=5152 width=20) (actual time=0.235..0.768 rows=5000 loops=1)
              Recheck Cond: (profile_id = 'p_hot'::text)
              Heap Blocks: exact=44
              ->  Bitmap Index Scan on playback_state_pkey
                    (cost=0.00..155.06 rows=5152 width=0) (actual time=0.226..0.226 rows=5000 loops=1)
                    Index Cond: (profile_id = 'p_hot'::text)
Planning Time: 0.284 ms
Execution Time: 1.467 ms
```

The primary key `(profile_id, title_id)` still finds the profile's rows, but it is not ordered by
`updated_at` — so Postgres pulls **all 5,000** matching rows out of the heap and `Sort`s them to
find the newest 20.

### Delta

| | with `idx_continue_watching` | without |
|---|---|---|
| top node | `Index Scan` | `Bitmap Heap Scan` + `Sort` |
| rows processed | 20 | 5,000 |
| sort step | none | top-N heapsort of 5,000 |
| execution time | 0.088 ms | 1.467 ms (~17x) |

The gap widens with profile size: the index path stays at "20 rows, stop"; the no-index path
scans and sorts every row the profile has.

---

## Resume (FR-3)

```sql
SELECT position_seconds, duration_seconds
FROM playback_state
WHERE profile_id = 'p_4271' AND title_id = 't_10';
```

Served by the primary key `(profile_id, title_id)` with no extra index — a single index lookup.
Not reproduced here; it is a plain `Index Scan using playback_state_pkey`.
