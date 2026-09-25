-- Every FAQ lookup filters by tenant.
CREATE INDEX IF NOT EXISTS faq_tenant_id_idx ON faq (tenant_id);
