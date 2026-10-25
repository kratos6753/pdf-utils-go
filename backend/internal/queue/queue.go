// Package queue enqueues PDF jobs onto Redis (asynq).
package queue

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/hibiken/asynq"
)

const TypeProcessJob = "job:process"

func RedisOpt(addr, password string) asynq.RedisClientOpt {
	return asynq.RedisClientOpt{Addr: addr, Password: password}
}

type Client struct {
	c       *asynq.Client
	timeout time.Duration
}

func NewClient(opt asynq.RedisClientOpt, jobTimeout time.Duration) *Client {
	return &Client{c: asynq.NewClient(opt), timeout: jobTimeout}
}

func (c *Client) Close() error { return c.c.Close() }

// EnqueueJob schedules processing of a job. The payload is only the job id;
// options (which may contain passwords) stay in Postgres.
func (c *Client) EnqueueJob(ctx context.Context, jobID uuid.UUID) error {
	_, err := c.c.EnqueueContext(ctx, asynq.NewTask(TypeProcessJob, []byte(jobID.String())),
		asynq.MaxRetry(2), asynq.Timeout(c.timeout), asynq.TaskID(jobID.String()))
	return err
}
