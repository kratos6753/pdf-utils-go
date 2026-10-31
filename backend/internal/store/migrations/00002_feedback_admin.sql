-- +goose Up
-- Messages from the Contact us form. They outlive the account (user_id is
-- cleared) so a conversation in progress isn't lost.
CREATE TABLE feedback (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid REFERENCES users (id) ON DELETE SET NULL,
    name       text NOT NULL DEFAULT '',
    email      text NOT NULL,
    topic      text NOT NULL DEFAULT 'feedback' CHECK (topic IN ('feedback', 'bug', 'question', 'other')),
    message    text NOT NULL,
    client     text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at    timestamptz
);
CREATE INDEX feedback_created_idx ON feedback (created_at DESC, id DESC);

-- Operators of the service, separate from app users.
CREATE TABLE admins (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email                text NOT NULL UNIQUE,
    password_hash        text NOT NULL,
    must_change_password boolean NOT NULL DEFAULT true,
    password_changed_at  timestamptz NOT NULL DEFAULT now(),
    last_login_at        timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now()
);

-- +goose Down
DROP TABLE admins;
DROP TABLE feedback;
