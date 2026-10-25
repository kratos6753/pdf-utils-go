// Package ratelimit implements fixed-window counters in Redis.
package ratelimit

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

type Limiter struct {
	rdb *redis.Client
}

func New(rdb *redis.Client) *Limiter { return &Limiter{rdb: rdb} }

type Result struct {
	Allowed   bool
	Limit     int
	Remaining int
	Reset     time.Time
}

// Allow counts one hit for key in the current window and reports whether it
// is within limit.
func (l *Limiter) Allow(ctx context.Context, key string, limit int, window time.Duration) (Result, error) {
	now := time.Now()
	start := now.Truncate(window)
	k := fmt.Sprintf("rl:%s:%d", key, start.Unix())
	pipe := l.rdb.TxPipeline()
	incr := pipe.Incr(ctx, k)
	pipe.ExpireNX(ctx, k, window+time.Minute)
	if _, err := pipe.Exec(ctx); err != nil {
		return Result{}, err
	}
	n := int(incr.Val())
	return Result{
		Allowed:   n <= limit,
		Limit:     limit,
		Remaining: max(0, limit-n),
		Reset:     start.Add(window),
	}, nil
}

// Peek returns usage for key in the current window without counting a hit.
func (l *Limiter) Peek(ctx context.Context, key string, limit int, window time.Duration) (Result, error) {
	start := time.Now().Truncate(window)
	n, err := l.rdb.Get(ctx, fmt.Sprintf("rl:%s:%d", key, start.Unix())).Int()
	if err != nil && err != redis.Nil {
		return Result{}, err
	}
	return Result{Allowed: n < limit, Limit: limit, Remaining: max(0, limit-n), Reset: start.Add(window)}, nil
}
