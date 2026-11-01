CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    api_key_hash TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS gmail_accounts (
    device_id TEXT PRIMARY KEY,
    email TEXT NOT NULL,
    refresh_token_ciphertext TEXT NOT NULL,
    refresh_token_iv TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE TABLE IF NOT EXISTS oauth_states (
    state_hash TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE TABLE IF NOT EXISTS reminders (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    title TEXT NOT NULL,
    target_date TEXT NOT NULL,
    recipient_email TEXT NOT NULL,
    reminder_time TEXT NOT NULL,
    timezone TEXT NOT NULL,
    notification_enabled INTEGER NOT NULL DEFAULT 1,
    email_enabled INTEGER NOT NULL DEFAULT 1,
    active INTEGER NOT NULL DEFAULT 1,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE INDEX IF NOT EXISTS reminders_device_idx ON reminders(device_id);
CREATE INDEX IF NOT EXISTS reminders_email_idx ON reminders(email_enabled, active, target_date);

CREATE TABLE IF NOT EXISTS email_sends (
    reminder_id TEXT NOT NULL,
    send_date TEXT NOT NULL,
    status TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (reminder_id, send_date)
);
