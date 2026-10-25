package store

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// Owner identifies who a file or job belongs to: a signed-in user, or an
// anonymous client identified by a hashed device id / IP.
type Owner struct {
	UserID  *uuid.UUID
	AnonKey string
}

func (o Owner) IsUser() bool { return o.UserID != nil }

// Subject is a stable string key for rate limiting and usage counters.
func (o Owner) Subject() string {
	if o.UserID != nil {
		return "u:" + o.UserID.String()
	}
	return "a:" + o.AnonKey
}

func (o Owner) anonKey() *string {
	if o.UserID != nil {
		return nil
	}
	return &o.AnonKey
}

type User struct {
	ID            uuid.UUID
	Email         string
	PasswordHash  *string
	GoogleSub     *string
	Name          string
	AvatarURL     string
	Plan          string
	EmailVerified bool
	CreatedAt     time.Time
}

type Limits struct {
	TasksPerHour    int `json:"tasks_per_hour"`
	MaxFileMB       int `json:"max_file_mb"`
	MaxFilesPerTask int `json:"max_files_per_task"`
	StorageMB       int `json:"storage_mb"`
}

type File struct {
	ID        uuid.UUID
	UserID    *uuid.UUID
	AnonKey   *string
	JobID     *uuid.UUID
	Kind      string // input | output
	Name      string
	Size      int64
	Mime      string
	S3Key     string
	ExpiresAt *time.Time
	CreatedAt time.Time
}

const (
	JobQueued     = "queued"
	JobProcessing = "processing"
	JobDone       = "done"
	JobFailed     = "failed"
)

type Job struct {
	ID           uuid.UUID
	UserID       *uuid.UUID
	AnonKey      *string
	Tool         string
	Options      json.RawMessage
	Status       string
	Progress     int
	ErrorCode    *string
	ErrorMessage *string
	InputIDs     []uuid.UUID
	OutputID     *uuid.UUID
	CreatedAt    time.Time
	StartedAt    *time.Time
	FinishedAt   *time.Time
}

func (j *Job) Owner() Owner {
	if j.UserID != nil {
		return Owner{UserID: j.UserID}
	}
	return Owner{AnonKey: *j.AnonKey}
}
