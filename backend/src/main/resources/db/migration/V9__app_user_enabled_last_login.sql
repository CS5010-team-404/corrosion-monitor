-- Admin > Users (frontend User type): whether an account may sign in, and when it last did.
-- Every existing account stays enabled. last_login_at is UTC, like locked_until and created_at.
ALTER TABLE app_user ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN last_login_at DATETIME(3) NULL;
