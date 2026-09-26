-- Models are loaded, replaced and deleted per tenant.
CREATE INDEX IF NOT EXISTS tenant_faqs_tenant_id_idx ON tenant_faqs (tenant_id);
