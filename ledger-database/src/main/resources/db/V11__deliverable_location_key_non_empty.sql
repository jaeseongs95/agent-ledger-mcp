-- Replace empty-string deliverable.location_key sentinel with non-empty ANSI sentinel (Oracle treats '' as NULL; REPO-033).

UPDATE deliverable SET location_key = '<none>' WHERE location IS NULL;
