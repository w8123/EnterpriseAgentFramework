package reachai

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
)

var capName = regexp.MustCompile(`^[A-Za-z0-9_.-]{1,192}$`)
var projectName = regexp.MustCompile(`^[a-z0-9_]+(?:-[a-z0-9_]+)*$`)
var indexName = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]{1,62}$`)
var indexIdentity = regexp.MustCompile(`^[A-Za-z0-9._:-]+$`)

type registeredCapability struct {
	definition    Capability
	input, output *schema
	registration  map[string]any
}

// Client holds server-side credentials and must never be made available to a browser.
type Client struct {
	mu                                                  sync.RWMutex
	options                                             Options
	deps                                                Dependencies
	http                                                *http.Client
	credential                                          *Credentials
	enrollment                                          string
	instanceID                                          string
	capabilities                                        map[string]*registeredCapability
	configured, mounted, starting, stopping, registered bool
	stop                                                context.CancelFunc
	done                                                chan struct{}
	nonces                                              CallbackNonceStore
	origins                                             map[string]bool
}

func (c *Client) String() string               { return "ReachAI{[REDACTED]}" }
func (c *Client) GoString() string             { return c.String() }
func (c *Client) MarshalJSON() ([]byte, error) { return []byte(`"[REDACTED]"`), nil }

// New constructs the integration; the application's own auth middleware remains authoritative.
func New(o Options, d Dependencies) (*Client, error) {
	if !projectName.MatchString(o.ProjectCode) {
		return nil, errors.New("invalid project code")
	}
	for _, value := range []string{o.RegistryURL, o.BaseURL} {
		u, err := url.Parse(value)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
			return nil, errors.New("invalid service URL")
		}
	}
	if (o.AppKey == "") != (o.AppSecret == "") || (o.EnrollmentToken != "" && o.AppKey != "") {
		return nil, errors.New("invalid credential configuration")
	}
	if o.InstanceID == "" {
		id, err := uuid()
		if err != nil {
			return nil, err
		}
		o.InstanceID = "go-" + id
	}
	client := &http.Client{Timeout: 30 * time.Second}
	if d.HTTPClient != nil {
		*client = *d.HTTPClient
		if client.Timeout == 0 {
			client.Timeout = 30 * time.Second
		}
	}
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	c := &Client{options: o, deps: d, http: client, enrollment: o.EnrollmentToken, instanceID: o.InstanceID, capabilities: map[string]*registeredCapability{}, origins: map[string]bool{}}
	c.options.AppSecret = ""
	c.options.EnrollmentToken = ""
	if o.AppKey != "" {
		c.credential = &Credentials{o.AppKey, o.AppSecret}
	}
	for _, value := range o.AllowedOrigins {
		v, err := normalizeOrigin(value)
		if err != nil {
			return nil, err
		}
		c.origins[v] = true
	}
	if d.NonceStore != nil {
		c.nonces = d.NonceStore
	} else {
		c.nonces = &memoryNonceStore{values: map[string]time.Time{}}
	}
	return c, nil
}

// RegisterCapability adds an explicitly allowed method before the handler is mounted.
func (c *Client) RegisterCapability(def Capability) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.mounted {
		return errors.New("register capabilities before mounting handler")
	}
	key := strings.ToLower(def.Name)
	if !capName.MatchString(def.Name) || c.capabilities[key] != nil {
		return errors.New("invalid or duplicate capability")
	}
	if def.SideEffect == "" {
		def.SideEffect = "WRITE"
	}
	switch def.SideEffect {
	case "READ", "WRITE", "IDEMPOTENT_WRITE", "IRREVERSIBLE":
	default:
		return errors.New("invalid side effect")
	}
	for _, role := range def.RequiredRoles {
		if strings.TrimSpace(role) == "" {
			return errors.New("invalid role")
		}
	}
	if (len(def.RequiredRoles) > 0 || def.BusinessIndexResolver) && !def.BusinessIdentityRequired {
		return errors.New("role and index capabilities require business identity")
	}
	if def.Handler == nil {
		return errors.New("capability handler required")
	}
	in, err := parseSchema(def.InputSchema)
	if err != nil {
		return err
	}
	if in.kind != "object" || in.nullable {
		return errors.New("capability input must be object")
	}
	out, err := parseSchema(def.OutputSchema)
	if err != nil {
		return err
	}
	if def.BusinessIndexResolver {
		if err = validateIndexSchema(in); err != nil {
			return err
		}
	}
	def.RequiredRoles = append([]string(nil), def.RequiredRoles...)
	def.InputSchema = in.wire
	def.OutputSchema = out.wire
	meta := map[string]any{"assetType": "BUSINESS_METHOD", "declared": true, "source": "ReachCapability", "requiredRoles": def.RequiredRoles, "businessIdentityRequired": def.BusinessIdentityRequired, "className": "go", "methodName": def.Name, "invokeProtocol": "REACHAI_CAPABILITY_HTTP", "timeoutMs": 0, "retryLimit": -1, "resultSchema": out.wire}
	if def.RequiredRoles == nil {
		meta["requiredRoles"] = []string{}
	}
	if def.BusinessIndexResolver {
		meta["tags"] = []string{"business-index-resolver"}
		delete(meta, "resultSchema")
	}
	registration := map[string]any{"name": def.Name, "title": def.Title, "description": def.Description, "httpMethod": "POST", "baseUrl": c.options.BaseURL, "contextPath": c.options.ContextPath, "endpointPath": "/reachai/capabilities/" + def.Name + "/invoke", "requestBodyType": "object", "responseType": out.kind, "sideEffect": def.SideEffect, "enabled": true, "parameters": in.children(), "metadata": meta}
	c.capabilities[key] = &registeredCapability{def, in, out, registration}
	c.configured = true
	return nil
}

// MarkEmptyInventoryComplete explicitly certifies that the full inventory is empty.
func (c *Client) MarkEmptyInventoryComplete() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.mounted {
		return errors.New("already mounted")
	}
	c.configured = true
	return nil
}

func (c *Client) signedPost(ctx context.Context, path string, body any, enrollment, bodySigned, projectOnly bool) (map[string]any, error) {
	data, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(c.options.RegistryURL, "/")+path, bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	c.mu.RLock()
	credential := c.credential
	token := c.enrollment
	c.mu.RUnlock()
	if enrollment {
		req.Header.Set("X-ReachAI-Registry-Enrollment-Token", token)
	} else {
		if credential == nil {
			return nil, errors.New("project credentials unavailable")
		}
		nonce, err := uuid()
		if err != nil {
			return nil, err
		}
		ts := strconv.FormatInt(time.Now().UnixMilli(), 10)
		signature, err := RegistrySignature(c.options.ProjectCode, ts, nonce, credential.AppSecret)
		if err != nil {
			return nil, err
		}
		req.Header.Set("X-ReachAI-App-Key", credential.AppKey)
		req.Header.Set("X-ReachAI-Timestamp", ts)
		req.Header.Set("X-ReachAI-Nonce", nonce)
		req.Header.Set("X-ReachAI-Signature", signature)
		if bodySigned {
			hash := sha256.Sum256(data)
			req.Header.Set("X-ReachAI-Body-SHA256", hex.EncodeToString(hash[:]))
			sig, err := BodySignature(http.MethodPost, path, c.options.ProjectCode, credential.AppKey, ts, nonce, data, credential.AppSecret)
			if err != nil {
				return nil, err
			}
			if projectOnly {
				req.Header.Set("X-ReachAI-Signature", sig)
			} else {
				req.Header.Set("X-ReachAI-Body-Signature", sig)
			}
		}
	}
	res, err := c.http.Do(req)
	if err != nil {
		return nil, errors.New("ReachAI request failed")
	}
	defer res.Body.Close()
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return nil, fmt.Errorf("ReachAI HTTP %d", res.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(res.Body, 8*1024*1024+1))
	if err != nil || len(b) > 8*1024*1024 {
		return nil, errors.New("invalid ReachAI response")
	}
	var result map[string]any
	if decodeJSON(b, &result) != nil || result == nil {
		return nil, errors.New("invalid ReachAI response")
	}
	if code, exists := result["code"]; exists && fmt.Sprint(code) != "200" {
		return nil, errors.New("ReachAI request rejected")
	}
	return result, nil
}
func dataMap(result map[string]any) map[string]any {
	if data, ok := result["data"].(map[string]any); ok {
		return data
	}
	return result
}
func (c *Client) projectPath(suffix string) string {
	return "/api/registry/projects/" + url.PathEscape(c.options.ProjectCode) + "/" + suffix
}

func (c *Client) register(ctx context.Context) error {
	c.mu.RLock()
	cred := c.credential
	c.mu.RUnlock()
	if cred == nil && c.deps.CredentialStore != nil {
		v, err := c.deps.CredentialStore.Load()
		if err != nil {
			return err
		}
		if v != nil {
			copy := *v
			if !copy.valid() {
				return errors.New("invalid stored credentials")
			}
			cred = &copy
			c.mu.Lock()
			c.credential = &copy
			c.mu.Unlock()
		}
	}
	enrollment := cred == nil
	if enrollment {
		if c.deps.CredentialStore == nil || c.enrollment == "" {
			return errors.New("enrollment requires token and durable credential store")
		}
		uncertain, err := c.deps.CredentialStore.EnrollmentUncertain()
		if err != nil {
			return err
		}
		if uncertain {
			return errors.New("enrollment outcome uncertain")
		}
		if err = c.deps.CredentialStore.MarkEnrollmentStarted(); err != nil {
			return err
		}
	}
	appKey := any(nil)
	if cred != nil {
		appKey = cred.AppKey
	}
	name := c.options.ProjectName
	if name == "" {
		name = c.options.ProjectCode
	}
	environment := c.options.Environment
	if environment == "" {
		environment = "default"
	}
	res, err := c.signedPost(ctx, "/api/registry/projects/register", map[string]any{"projectCode": c.options.ProjectCode, "name": name, "environment": environment, "visibility": "PRIVATE", "baseUrl": c.options.BaseURL, "contextPath": c.options.ContextPath, "appKey": appKey}, enrollment, false, false)
	if err != nil {
		return err
	}
	p := dataMap(res)
	if p["projectCode"] != c.options.ProjectCode {
		return errors.New("registration project mismatch")
	}
	if enrollment {
		key, _ := p["appKey"].(string)
		secret, _ := p["appSecret"].(string)
		v := Credentials{key, secret}
		if !v.valid() {
			return errors.New("registration credentials missing")
		}
		if err = c.deps.CredentialStore.Save(v); err != nil {
			return err
		}
		c.mu.Lock()
		c.credential = &v
		c.enrollment = ""
		c.mu.Unlock()
		if err = c.deps.CredentialStore.ClearEnrollmentStarted(); err != nil {
			return err
		}
	}
	c.mu.Lock()
	c.registered = true
	c.mu.Unlock()
	return nil
}
func (c *Client) heartbeat(ctx context.Context) error {
	host, _ := os.Hostname()
	body := map[string]any{"instanceId": c.instanceID, "baseUrl": c.options.BaseURL, "host": host, "sdkVersion": "1.0.0", "metadata": map[string]any{"runtimePlacement": "CAPABILITY_HOST", "runtimeTypes": []string{"GO_HTTP_CAPABILITY_HOST"}, "supportsTools": true, "sdkLanguage": "go"}}
	res, err := c.signedPost(ctx, c.projectPath("instances/heartbeat"), body, false, false, false)
	if err != nil {
		return err
	}
	instance, ok := dataMap(res)["instance"].(map[string]any)
	if !ok || instance["instanceId"] != c.instanceID {
		return errors.New("incomplete heartbeat response")
	}
	return nil
}

// Sync submits a complete inventory. An identical report may return the old snapshot ID.
func (c *Client) Sync(ctx context.Context, source string) (map[string]any, error) {
	c.mu.RLock()
	if !c.configured || !c.mounted {
		c.mu.RUnlock()
		return nil, errors.New("capability inventory not configured and mounted")
	}
	items := make([]map[string]any, 0, len(c.capabilities))
	for _, v := range c.capabilities {
		items = append(items, v.registration)
	}
	c.mu.RUnlock()
	// Stable ordering ensures identical declarations remain the same source inventory.
	sortCapabilities(items)
	id, err := uuid()
	if err != nil {
		return nil, err
	}
	if source == "" {
		source = "SDK"
	}
	res, err := c.signedPost(ctx, c.projectPath("capabilities/sync"), map[string]any{"syncId": id, "source": source, "apply": false, "capabilities": items}, false, false, false)
	if err != nil {
		return nil, err
	}
	p := dataMap(res)
	snapshot, ok := p["syncId"].(string)
	n, number := p["received"].(json.Number)
	received, parseErr := n.Int64()
	if !ok || strings.TrimSpace(snapshot) == "" || len(utf16.Encode([]rune(snapshot))) > 64 || p["projectCode"] != c.options.ProjectCode || !number || parseErr != nil || received != int64(len(items)) {
		return nil, errors.New("incomplete capability sync response")
	}
	return res, nil
}

// Start registers once and starts serial heartbeats. Stop must be called during shutdown.
func (c *Client) Start(ctx context.Context) error {
	c.mu.Lock()
	if !c.mounted || !c.configured || c.starting || c.stopping || c.stop != nil {
		c.mu.Unlock()
		return errors.New("mount a configured inventory before starting; do not start twice")
	}
	if c.requiresBridgeLocked() && c.deps.SecurityBridge == nil {
		c.mu.Unlock()
		return errors.New("business identity bridge required")
	}
	c.starting = true
	c.mu.Unlock()
	defer func() { c.mu.Lock(); c.starting = false; c.mu.Unlock() }()
	if err := c.register(ctx); err != nil {
		return err
	}
	if err := c.heartbeat(ctx); err != nil {
		return err
	}
	worker, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	c.mu.Lock()
	c.stop = cancel
	c.done = done
	c.mu.Unlock()
	interval := c.options.HeartbeatInterval
	if interval < 5*time.Second {
		interval = 30 * time.Second
	}
	go func() {
		defer close(done)
		t := time.NewTicker(interval)
		defer t.Stop()
		for {
			select {
			case <-worker.Done():
				return
			case <-t.C:
				// Stop cancels the scheduling loop, not an already-sent heartbeat.
				// Wait for its bounded HTTP result before issuing the offline request.
				_ = c.heartbeat(context.WithoutCancel(ctx))
			}
		}
	}()
	return nil
}

// Stop waits for the in-flight heartbeat before marking the instance offline.
func (c *Client) Stop(ctx context.Context) error {
	c.mu.Lock()
	if c.starting || c.stopping {
		c.mu.Unlock()
		return errors.New("lifecycle operation already running")
	}
	c.stopping = true
	cancel, done, registered := c.stop, c.done, c.registered
	c.mu.Unlock()
	defer func() { c.mu.Lock(); c.stopping = false; c.mu.Unlock() }()
	if cancel != nil {
		cancel()
	}
	if done != nil {
		select {
		case <-done:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	c.mu.Lock()
	if c.done == done {
		c.stop = nil
		c.done = nil
	}
	c.mu.Unlock()
	if registered {
		_, err := c.signedPost(ctx, c.projectPath("instances/offline"), map[string]any{"instanceId": c.instanceID}, false, false, false)
		if err != nil {
			return err
		}
		c.mu.Lock()
		c.registered = false
		c.mu.Unlock()
	}
	return nil
}
func (c *Client) requiresBridgeLocked() bool {
	for _, v := range c.capabilities {
		if v.definition.BusinessIdentityRequired {
			return true
		}
	}
	return false
}
func sortCapabilities(items []map[string]any) {
	sort.Slice(items, func(i, j int) bool { return items[i]["name"].(string) < items[j]["name"].(string) })
}
