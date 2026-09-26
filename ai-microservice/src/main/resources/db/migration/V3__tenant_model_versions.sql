-- Incremented on every retrain, so each AI instance can tell when its in-memory model for a tenant is stale
-- (another instance retrained it) and reload it.
CREATE TABLE tenant_model_versions (
    tenant_id bigint PRIMARY KEY,
    version   bigint NOT NULL
);
