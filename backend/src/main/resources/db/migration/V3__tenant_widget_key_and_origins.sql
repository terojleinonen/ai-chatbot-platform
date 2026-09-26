-- Public chat: widgets identify their tenant by an unguessable key instead of the sequential id,
-- and a tenant may restrict which websites (origins) can use its chat.
ALTER TABLE tenant ADD COLUMN widget_key varchar(64);
ALTER TABLE tenant ADD COLUMN allowed_origins varchar(4000);

-- gen_random_uuid() uses a cryptographically secure generator (PostgreSQL 13+): 32 hex chars, 122 random bits.
UPDATE tenant SET widget_key = replace(gen_random_uuid()::text, '-', '') WHERE widget_key IS NULL;

ALTER TABLE tenant ALTER COLUMN widget_key SET NOT NULL;
ALTER TABLE tenant ADD CONSTRAINT tenant_widget_key_key UNIQUE (widget_key);
