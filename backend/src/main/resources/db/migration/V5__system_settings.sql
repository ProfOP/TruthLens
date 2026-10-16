-- SRS §4 administrator system control: persisted operational settings, one row per key.
-- 'submissions.enabled' gates only new analysis submissions; it is read on every request so a
-- change takes effect without a restart. Stored analyses, assets and reports are never touched.
CREATE TABLE IF NOT EXISTS system_settings (
  setting_key VARCHAR(64) PRIMARY KEY,
  setting_value TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by UUID REFERENCES users(id)
);

INSERT INTO system_settings (setting_key, setting_value) VALUES ('submissions.enabled', 'true')
  ON CONFLICT (setting_key) DO NOTHING;
