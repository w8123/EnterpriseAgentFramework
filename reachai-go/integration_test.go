package reachai

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func obj(properties map[string]any, required ...string) map[string]any {
	if required == nil {
		required = []string{}
	}
	return map[string]any{"type": "object", "properties": properties, "required": required}
}
func scalar(kind string) map[string]any { return map[string]any{"type": kind} }
func mustClient(t *testing.T, o Options, d Dependencies) *Client {
	t.Helper()
	c, e := New(o, d)
	if e != nil {
		t.Fatal(e)
	}
	return c
}
func mustRequest(t *testing.T, client *http.Client, endpoint string, body any, headers map[string]string) (int, map[string]any) {
	t.Helper()
	b, e := json.Marshal(body)
	if e != nil {
		t.Fatal(e)
	}
	r, e := http.NewRequest("POST", endpoint, bytes.NewReader(b))
	if e != nil {
		t.Fatal(e)
	}
	r.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		r.Header.Set(k, v)
	}
	res, e := client.Do(r)
	if e != nil {
		t.Fatal(e)
	}
	defer res.Body.Close()
	var data map[string]any
	if e = json.NewDecoder(res.Body).Decode(&data); e != nil {
		t.Fatal(e)
	}
	return res.StatusCode, data
}
func TestExpressedInventoryIdentityEmbedAndRepeatedSync(t *testing.T) {
	var mu sync.Mutex
	var syncIDs []string
	var exchange map[string]any
	var indexSigs int
	var offlines int
	platform := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		var body map[string]any
		_ = decodeJSON(b, &body)
		switch {
		case strings.HasSuffix(r.URL.Path, "/register"):
			respond(w, 200, map[string]any{"projectCode": "demo"})
		case strings.HasSuffix(r.URL.Path, "/heartbeat"):
			respond(w, 200, map[string]any{"instance": map[string]any{"instanceId": body["instanceId"]}})
		case strings.HasSuffix(r.URL.Path, "/offline"):
			mu.Lock()
			offlines++
			mu.Unlock()
			respond(w, 200, map[string]any{"code": 200})
		case strings.HasSuffix(r.URL.Path, "/capabilities/sync"):
			mu.Lock()
			syncIDs = append(syncIDs, body["syncId"].(string))
			old := syncIDs[0]
			mu.Unlock()
			caps := body["capabilities"].([]any)
			respond(w, 200, map[string]any{"syncId": old, "projectCode": "demo", "received": len(caps)})
		case strings.HasSuffix(r.URL.Path, "/token/exchange"), strings.HasSuffix(r.URL.Path, "/business-session/revoke"):
			sig, e := BodySignature("POST", r.URL.Path, "demo", "key", r.Header.Get("X-ReachAI-Timestamp"), r.Header.Get("X-ReachAI-Nonce"), b, "secret")
			if e != nil || sig != r.Header.Get("X-ReachAI-Body-Signature") {
				t.Error("bad embed body signature")
			}
			if strings.HasSuffix(r.URL.Path, "/exchange") {
				mu.Lock()
				exchange = body
				mu.Unlock()
				respond(w, 200, map[string]any{"code": 200, "data": map[string]any{"token": "fixture-token", "expiresIn": 60, "businessSessionBound": true, "businessSessionExpiryApplied": true}})
			} else {
				respond(w, 200, map[string]any{"code": 200})
			}
		case strings.Contains(r.URL.Path, "/biz-index/"):
			sig, e := BodySignature("POST", r.URL.Path, "demo", "key", r.Header.Get("X-ReachAI-Timestamp"), r.Header.Get("X-ReachAI-Nonce"), b, "secret")
			if e != nil || sig != r.Header.Get("X-ReachAI-Signature") {
				t.Error("bad index signature")
			}
			mu.Lock()
			indexSigs++
			mu.Unlock()
			respond(w, 200, map[string]any{"code": 200})
		default:
			respond(w, 404, map[string]any{"error": "unexpected"})
		}
	}))
	defer platform.Close()
	c := mustClient(t, Options{RegistryURL: platform.URL, ProjectCode: "demo", BaseURL: "http://biz.example", AppKey: "key", AppSecret: "secret", AllowedOrigins: []string{"http://biz.example"}}, Dependencies{
		SecurityBridge: func(ctx context.Context, id Invocation, next func(context.Context) (any, error)) (any, error) {
			return next(ctx)
		},
		PrincipalResolver: func(r *http.Request, id string) (*EmbedPrincipal, error) {
			if r.Header.Get("X-Test-Auth") != "yes" {
				return nil, nil
			}
			return &EmbedPrincipal{ExternalUserID: "alice", Roles: []string{"Customer"}, Attributes: map[string]any{"tenantId": "tenant1"}, BusinessSessionID: "private-session", BusinessSessionExpiresAt: 1999999999}, nil
		},
	})
	input := obj(map[string]any{"orderId": scalar("integer"), "filter": obj(map[string]any{"status": scalar("string")}, "status")}, "orderId", "filter")
	output := obj(map[string]any{"user": scalar("string"), "tenant": scalar("string"), "orderId": scalar("integer")}, "user", "tenant", "orderId")
	err := c.RegisterCapability(Capability{Name: "order.query", SideEffect: "READ", BusinessIdentityRequired: true, RequiredRoles: []string{"Customer"}, InputSchema: input, OutputSchema: output, Handler: func(ctx context.Context, args map[string]any) (any, error) {
		id, ok := CurrentInvocation(ctx)
		if !ok {
			return nil, errors.New("missing identity")
		}
		time.Sleep(time.Millisecond)
		again, _ := CurrentInvocation(ctx)
		if id.ExternalUserID != again.ExternalUserID {
			return nil, errors.New("identity crossed")
		}
		return map[string]any{"user": id.ExternalUserID, "tenant": id.TenantID, "orderId": args["orderId"]}, nil
	}})
	if err != nil {
		t.Fatal(err)
	}
	app := httptest.NewServer(c.Handler())
	defer app.Close()
	if err = c.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := c.Stop(context.Background()); err != nil {
			t.Error(err)
		}
	}()
	var wg sync.WaitGroup
	var failures atomic.Int32
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			user := "bob"
			if i%2 == 1 {
				user = "alice"
			}
			token := signedTestToken(t, "secret", map[string]any{"alg": "HS256"}, map[string]any{"projectCode": "demo", "appKey": "key", "capabilityName": "order.query", "exp": time.Now().Unix() + 60, "externalUserId": user, "tenantId": "tenant1", "roles": []string{"Customer"}})
			status, result := mustRequest(t, app.Client(), app.URL+"/reachai/capabilities/order.query/invoke", map[string]any{"orderId": i, "filter": map[string]any{"status": "open"}}, map[string]string{"X-ReachAI-Invocation-Token": token})
			if status != 200 || result["user"] != user {
				failures.Add(1)
			}
		}(i)
	}
	wg.Wait()
	if failures.Load() != 0 {
		t.Fatal("concurrent identities crossed")
	}
	token := signedTestToken(t, "secret", map[string]any{"alg": "HS256"}, map[string]any{"projectCode": "demo", "appKey": "key", "capabilityName": "order.query", "exp": time.Now().Unix() + 60, "externalUserId": "alice", "tenantId": "tenant1", "roles": []string{"Customer"}})
	status, _ := mustRequest(t, app.Client(), app.URL+"/reachai/capabilities/order.query/invoke", map[string]any{"orderId": 1, "filter": map[string]any{"status": "open", "extra": true}}, map[string]string{"X-ReachAI-Invocation-Token": token})
	if status != 400 {
		t.Fatalf("nested extras status %d", status)
	}
	status, _ = mustRequest(t, app.Client(), app.URL+"/reachai/capabilities/order.query/invoke", map[string]any{"orderId": 1, "filter": map[string]any{"status": "open"}}, map[string]string{"X-ReachAI-Invocation-Token": "forged"})
	if status != 401 {
		t.Fatal("forged invocation accepted")
	}
	for _, tc := range []struct {
		user, project string
		roles         []string
		expected      int
	}{
		{"", "demo", []string{"Customer"}, 401}, {"alice", "other", []string{"Customer"}, 401}, {"alice", "demo", []string{"Admin"}, 403},
	} {
		restricted := signedTestToken(t, "secret", map[string]any{"alg": "HS256"}, map[string]any{"projectCode": tc.project, "appKey": "key", "capabilityName": "order.query", "exp": time.Now().Unix() + 60, "externalUserId": tc.user, "tenantId": "tenant1", "roles": tc.roles})
		status, _ = mustRequest(t, app.Client(), app.URL+"/reachai/capabilities/order.query/invoke", map[string]any{"orderId": 1, "filter": map[string]any{"status": "open"}}, map[string]string{"X-ReachAI-Invocation-Token": restricted})
		if status != tc.expected {
			t.Fatalf("invalid identity or roles accepted: %d", status)
		}
	}
	for i := 0; i < 2; i++ {
		nonce := fmt.Sprintf("sync-nonce-%d", i)
		ts := fmt.Sprint(time.Now().UnixMilli())
		sig, _ := RegistrySignature("demo", ts, nonce, "secret")
		headers := map[string]string{"X-ReachAI-App-Key": "key", "X-ReachAI-Timestamp": ts, "X-ReachAI-Nonce": nonce, "X-ReachAI-Signature": sig}
		status, _ = mustRequest(t, app.Client(), app.URL+"/reachai/registry/capabilities/sync", map[string]any{}, headers)
		if status != 200 {
			t.Fatalf("sync callback %d failed %d", i, status)
		}
		if i == 0 {
			status, _ = mustRequest(t, app.Client(), app.URL+"/reachai/registry/capabilities/sync", map[string]any{}, headers)
			if status != 401 {
				t.Fatal("replayed callback accepted")
			}
		}
	}
	mu.Lock()
	if len(syncIDs) != 2 || syncIDs[0] == syncIDs[1] {
		t.Error("expected two distinct requests")
	}
	mu.Unlock()
	data := map[string]any{"agentId": "agent", "pageInstanceId": "page", "origin": "http://biz.example"}
	status, _ = mustRequest(t, app.Client(), app.URL+"/reachai/embed/token", data, map[string]string{"Origin": "http://evil.example", "X-Test-Auth": "yes"})
	if status != 403 {
		t.Fatal("evil origin accepted")
	}
	status, _ = mustRequest(t, app.Client(), app.URL+"/reachai/embed/token", map[string]any{"agentId": "agent", "pageInstanceId": "page", "externalUserId": "admin"}, map[string]string{"Origin": "http://biz.example", "X-Test-Auth": "yes"})
	if status != 400 {
		t.Fatal("browser supplied identity accepted")
	}
	for _, bad := range []map[string]any{{"pageKey": map[string]any{}}, {"route": []string{"/"}}} {
		body := map[string]any{"agentId": "agent", "pageInstanceId": "page"}
		for key, value := range bad {
			body[key] = value
		}
		status, _ = mustRequest(t, app.Client(), app.URL+"/reachai/embed/token", body, map[string]string{"Origin": "http://biz.example", "X-Test-Auth": "yes"})
		if status != 400 {
			t.Fatal("invalid page context type accepted")
		}
	}
	status, _ = mustRequest(t, app.Client(), app.URL+"/reachai/embed/token", data, map[string]string{"Origin": "http://biz.example"})
	if status != 401 {
		t.Fatal("anonymous embed accepted")
	}
	status, _ = mustRequest(t, app.Client(), app.URL+"/reachai/embed/token", data, map[string]string{"Origin": "http://biz.example", "X-Test-Auth": "yes"})
	if status != 200 {
		t.Fatal("trusted embed rejected")
	}
	mu.Lock()
	principal := exchange["principal"].(map[string]any)
	if principal["externalUserId"] != "alice" || principal["attributes"].(map[string]any)["tenantId"] != "tenant1" || principal["roles"].([]any)[0] != "Customer" || exchange["businessSessionId"] != "private-session" {
		t.Error("embed wire identity or session wrong")
	}
	mu.Unlock()
	if err = c.RevokeBusinessSession(context.Background(), "private-session"); err != nil {
		t.Fatal(err)
	}
	if _, err = c.IndexUpsert(context.Background(), "orders_idx", map[string]any{"id": "42"}); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	if indexSigs != 1 {
		t.Error("index signature not checked")
	}
	mu.Unlock()
}

func TestCredentialDurabilityUncertainEnrollmentAndRedaction(t *testing.T) {
	key := bytes.Repeat([]byte{3}, 32)
	path := filepath.Join(t.TempDir(), "credentials")
	store, err := NewEncryptedFileCredentialStore(path, key)
	if err != nil {
		t.Fatal(err)
	}
	var calls atomic.Int32
	platform := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); respond(w, 500, map[string]any{}) }))
	defer platform.Close()
	c := mustClient(t, Options{RegistryURL: platform.URL, ProjectCode: "demo", BaseURL: "http://host", EnrollmentToken: "sensitive-token"}, Dependencies{CredentialStore: store})
	for _, v := range []string{fmt.Sprintf("%+v", c), fmt.Sprintf("%#v", c), fmt.Sprintf("%+v", store), fmt.Sprintf("%#v", Credentials{"key", "sensitive-secret"})} {
		if strings.Contains(v, "sensitive") {
			t.Fatal("secret rendered")
		}
	}
	_ = c.MarkEmptyInventoryComplete()
	_ = c.Handler()
	if c.Start(context.Background()) == nil {
		t.Fatal("failed enrollment accepted")
	}
	if c.Start(context.Background()) == nil {
		t.Fatal("uncertain enrollment replayed")
	}
	if calls.Load() != 1 {
		t.Fatal("one-time enrollment was replayed")
	}
	other, err := NewEncryptedFileCredentialStore(filepath.Join(t.TempDir(), "credential"), key)
	if err != nil {
		t.Fatal(err)
	}
	if err = other.Save(Credentials{"key", "sensitive-secret"}); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(other.path)
	if err != nil || bytes.Contains(b, []byte("sensitive-secret")) {
		t.Fatal("credentials were not encrypted")
	}
	loaded, err := other.Load()
	if err != nil || loaded.AppSecret != "sensitive-secret" {
		t.Fatal("credential recovery failed")
	}
	if other.Save(Credentials{"key", "new"}) == nil {
		t.Fatal("credential overwrite accepted")
	}
}

func TestInputSchemaAndRequiredIdentityFailClosed(t *testing.T) {
	c := mustClient(t, Options{RegistryURL: "http://registry", ProjectCode: "demo", BaseURL: "http://host", AppKey: "key", AppSecret: "secret"}, Dependencies{})
	_ = c.Handler()
	if c.Start(context.Background()) == nil {
		t.Fatal("unknown inventory permitted startup")
	}
	for _, schema := range []map[string]any{{"type": "string", "format": "email"}, {"type": "string", "$ref": "x"}, {"type": "string", "required": []string{"x"}}, {"type": "array", "items": map[string]any{"type": "string", "pattern": "x"}}} {
		if _, e := parseSchema(schema); e == nil {
			t.Fatalf("unsupported schema accepted: %v", schema)
		}
	}
	if _, e := AuthorizeIndexRow(IndexRowAuthorization{InvocationTenantID: "t", AuthoritativeRowTenantID: "t", CurrentUserID: strings.Repeat("中", 120), ProjectCode: "p", ResourceType: "order", RequestedResourceID: "单一", AuthoritativeResourceID: "单一", VisibleToCurrentUser: true}); e != nil {
		t.Fatalf("valid Chinese user ID was rejected: %v", e)
	}
}

func TestEnrollmentStoresCredentialsAndLaterRegistrationUsesSignature(t *testing.T) {
	key := bytes.Repeat([]byte{8}, 32)
	store, err := NewEncryptedFileCredentialStore(filepath.Join(t.TempDir(), "credentials"), key)
	if err != nil {
		t.Fatal(err)
	}
	var registrations atomic.Int32
	platform := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		switch {
		case strings.HasSuffix(r.URL.Path, "/register"):
			if registrations.Add(1) == 1 {
				if r.Header.Get("X-ReachAI-Registry-Enrollment-Token") != "one-time" {
					t.Error("missing enrollment token")
				}
				respond(w, 200, map[string]any{"projectCode": "demo", "appKey": "key", "appSecret": "secret"})
			} else {
				ts, nonce := r.Header.Get("X-ReachAI-Timestamp"), r.Header.Get("X-ReachAI-Nonce")
				sig, _ := RegistrySignature("demo", ts, nonce, "secret")
				if r.Header.Get("X-ReachAI-Signature") != sig || r.Header.Get("X-ReachAI-Registry-Enrollment-Token") != "" {
					t.Error("subsequent registration was not signed")
				}
				respond(w, 200, map[string]any{"projectCode": "demo"})
			}
		case strings.HasSuffix(r.URL.Path, "/heartbeat"):
			respond(w, 200, map[string]any{"instance": map[string]any{"instanceId": body["instanceId"]}})
		case strings.HasSuffix(r.URL.Path, "/offline"):
			respond(w, 200, map[string]any{"code": 200})
		default:
			respond(w, 404, map[string]any{})
		}
	}))
	defer platform.Close()
	options := Options{RegistryURL: platform.URL, ProjectCode: "demo", BaseURL: "http://host", EnrollmentToken: "one-time"}
	for i := 0; i < 2; i++ {
		c := mustClient(t, options, Dependencies{CredentialStore: store})
		if err = c.MarkEmptyInventoryComplete(); err != nil {
			t.Fatal(err)
		}
		_ = c.Handler()
		if err = c.Start(context.Background()); err != nil {
			t.Fatal(err)
		}
		if err = c.Stop(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	if registrations.Load() != 2 {
		t.Fatal("registration count mismatch")
	}
	uncertain, err := store.EnrollmentUncertain()
	if err != nil || uncertain {
		t.Fatal("successful enrollment marker not cleared")
	}
}

func TestSyncValidatesReusedSnapshotResponse(t *testing.T) {
	var response map[string]any
	var mu sync.RWMutex
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.RLock()
		defer mu.RUnlock()
		respond(w, 200, response)
	}))
	defer server.Close()
	c := mustClient(t, Options{RegistryURL: server.URL, ProjectCode: "demo", BaseURL: "http://host", AppKey: "key", AppSecret: "secret"}, Dependencies{})
	if err := c.MarkEmptyInventoryComplete(); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Sync(context.Background(), ""); err == nil {
		t.Fatal("unmounted inventory was allowed to sync")
	}
	_ = c.Handler()
	mu.Lock()
	response = map[string]any{"syncId": "snapshot-existing", "projectCode": "demo", "received": 0}
	mu.Unlock()
	if _, err := c.Sync(context.Background(), ""); err != nil {
		t.Fatalf("valid reused snapshot rejected: %v", err)
	}
	for _, item := range []map[string]any{{"syncId": "", "projectCode": "demo", "received": 0}, {"syncId": strings.Repeat("x", 65), "projectCode": "demo", "received": 0}, {"syncId": "original", "projectCode": "other", "received": 0}, {"syncId": "original", "projectCode": "demo", "received": true}, {"syncId": "original", "projectCode": "demo", "received": 1}, {"code": 500}} {
		mu.Lock()
		response = item
		mu.Unlock()
		if _, err := c.Sync(context.Background(), ""); err == nil {
			t.Fatal("invalid sync response accepted")
		}
	}
}
