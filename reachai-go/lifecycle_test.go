package reachai

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestConcurrentStartAndHeartbeatShutdownOrder(t *testing.T) {
	registerEntered, releaseRegister := make(chan struct{}), make(chan struct{})
	heartbeatEntered, releaseHeartbeat := make(chan struct{}), make(chan struct{})
	var registers, heartbeats, offlines atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/register"):
			if registers.Add(1) == 1 {
				close(registerEntered)
				<-releaseRegister
			}
			respond(w, 200, map[string]any{"projectCode": "demo"})
		case strings.HasSuffix(r.URL.Path, "/heartbeat"):
			var b map[string]any
			_ = json.NewDecoder(r.Body).Decode(&b)
			if heartbeats.Add(1) == 2 {
				close(heartbeatEntered)
				<-releaseHeartbeat
			}
			respond(w, 200, map[string]any{"instance": map[string]any{"instanceId": b["instanceId"]}})
		case strings.HasSuffix(r.URL.Path, "/offline"):
			offlines.Add(1)
			respond(w, 200, map[string]any{"code": 200})
		default:
			respond(w, 404, map[string]any{})
		}
	}))
	defer server.Close()
	c := mustClient(t, Options{RegistryURL: server.URL, ProjectCode: "demo", BaseURL: "http://host", AppKey: "key", AppSecret: "secret", HeartbeatInterval: 5 * time.Second}, Dependencies{})
	if err := c.MarkEmptyInventoryComplete(); err != nil {
		t.Fatal(err)
	}
	_ = c.Handler()
	started := make(chan error, 1)
	go func() { started <- c.Start(context.Background()) }()
	select {
	case <-registerEntered:
	case <-time.After(5 * time.Second):
		t.Fatal("registration did not start")
	}
	if err := c.Start(context.Background()); err == nil {
		t.Fatal("concurrent start accepted")
	}
	if err := c.Stop(context.Background()); err == nil {
		t.Fatal("stop during startup accepted")
	}
	close(releaseRegister)
	if err := <-started; err != nil {
		t.Fatal(err)
	}
	select {
	case <-heartbeatEntered:
	case <-time.After(8 * time.Second):
		t.Fatal("periodic heartbeat did not start")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if err := c.Stop(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected bounded timeout, got %v", err)
	}
	if offlines.Load() != 0 {
		t.Fatal("offline raced ahead of in-flight heartbeat")
	}
	close(releaseHeartbeat)
	if err := c.Stop(context.Background()); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); _ = c.Stop(context.Background()) }()
	}
	wg.Wait()
	if registers.Load() != 1 || offlines.Load() != 1 {
		t.Fatalf("unexpected registrations/offlines: %d/%d", registers.Load(), offlines.Load())
	}
}
