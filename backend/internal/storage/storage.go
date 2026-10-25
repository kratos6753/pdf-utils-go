// Package storage wraps S3-compatible object storage (AWS S3, Cloudflare R2, MinIO).
package storage

import (
	"context"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/url"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"

	"pdfutils/internal/config"
)

type Storage struct {
	client  *s3.Client
	presign *s3.PresignClient // nil => clients download through the API
	bucket  string
}

func New(ctx context.Context, c *config.Config) (*Storage, error) {
	opts := []func(*awsconfig.LoadOptions) error{awsconfig.WithRegion(c.S3Region)}
	if c.S3AccessKey != "" {
		opts = append(opts, awsconfig.WithCredentialsProvider(
			credentials.NewStaticCredentialsProvider(c.S3AccessKey, c.S3SecretKey, "")))
	}
	awsCfg, err := awsconfig.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		return nil, err
	}
	newClient := func(endpoint string) *s3.Client {
		return s3.NewFromConfig(awsCfg, func(o *s3.Options) {
			if endpoint != "" {
				o.BaseEndpoint = aws.String(endpoint)
			}
			o.UsePathStyle = c.S3PathStyle
		})
	}
	s := &Storage{client: newClient(c.S3Endpoint), bucket: c.S3Bucket}
	switch {
	case c.S3PublicEndpoint != "":
		s.presign = s3.NewPresignClient(newClient(c.S3PublicEndpoint))
	case c.S3Endpoint == "":
		s.presign = s3.NewPresignClient(s.client) // real AWS: endpoint is public
	}
	return s, nil
}

// EnsureBucket creates the bucket if it does not exist (dev convenience).
func (s *Storage) EnsureBucket(ctx context.Context) error {
	if _, err := s.client.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: &s.bucket}); err == nil {
		return nil
	}
	_, err := s.client.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: &s.bucket})
	var owned *types.BucketAlreadyOwnedByYou
	if errors.As(err, &owned) {
		return nil
	}
	return err
}

func (s *Storage) Ping(ctx context.Context) error {
	_, err := s.client.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: &s.bucket})
	return err
}

func (s *Storage) Put(ctx context.Context, key string, r io.Reader, size int64, contentType string) error {
	_, err := s.client.PutObject(ctx, &s3.PutObjectInput{
		Bucket:        &s.bucket,
		Key:           &key,
		Body:          r,
		ContentLength: aws.Int64(size),
		ContentType:   &contentType,
	})
	return err
}

func (s *Storage) Get(ctx context.Context, key string) (io.ReadCloser, error) {
	out, err := s.client.GetObject(ctx, &s3.GetObjectInput{Bucket: &s.bucket, Key: &key})
	if err != nil {
		return nil, err
	}
	return out.Body, nil
}

func (s *Storage) Delete(ctx context.Context, key string) error {
	_, err := s.client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: &s.bucket, Key: &key})
	return err
}

// CanPresign reports whether clients can download directly from storage.
func (s *Storage) CanPresign() bool { return s.presign != nil }

// PresignGet returns a temporary download URL that saves as filename.
func (s *Storage) PresignGet(ctx context.Context, key, filename string, ttl time.Duration) (string, error) {
	if s.presign == nil {
		return "", fmt.Errorf("presigning not configured")
	}
	req, err := s.presign.PresignGetObject(ctx, &s3.GetObjectInput{
		Bucket:                     &s.bucket,
		Key:                        &key,
		ResponseContentDisposition: aws.String(ContentDisposition(filename)),
	}, s3.WithPresignExpires(ttl))
	if err != nil {
		return "", err
	}
	return req.URL, nil
}

// ContentDisposition builds an attachment header that is safe for any filename.
func ContentDisposition(filename string) string {
	if v := mime.FormatMediaType("attachment", map[string]string{"filename": filename}); v != "" {
		return v
	}
	return "attachment; filename*=UTF-8''" + url.PathEscape(filename)
}
