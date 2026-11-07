package reachai

import (
	"context"
	"crypto/hmac"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type memoryNonceStore struct {
	mu     sync.Mutex
	values map[string]time.Time
}

func (s *memoryNonceStore) Claim(ctx context.Context, project, nonce string, expiry time.Time) (bool, error) {
	if err := ctx.Err(); err != nil {
		return false, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	for k, v := range s.values {
		if !v.After(now) {
			delete(s.values, k)
		}
	}
	key := project + "\x00" + nonce
	if _, ok := s.values[key]; ok {
		return false, nil
	}
	s.values[key] = expiry
	return true, nil
}
func normalizeOrigin(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || u.Opaque != "" {
		return "", errors.New("invalid origin")
	}
	host := strings.ToLower(u.Hostname())
	if host == "" {
		return "", errors.New("invalid origin")
	}
	port := u.Port()
	if port == "80" && u.Scheme == "http" || port == "443" && u.Scheme == "https" {
		port = ""
	}
	if port != "" {
		n, e := strconv.Atoi(port)
		if e != nil || n < 1 || n > 65535 {
			return "", errors.New("invalid origin port")
		}
		host = net.JoinHostPort(host, port)
	} else if strings.Contains(host, ":") {
		host = "[" + host + "]"
	}
	return u.Scheme + "://" + host, nil
}
func (c *Client) verifyCallback(ctx context.Context, h http.Header) bool {
	c.mu.RLock()
	credential := c.credential
	c.mu.RUnlock()
	if credential == nil {
		return false
	}
	key, ts, nonce, sig := h.Get("X-ReachAI-App-Key"), h.Get("X-ReachAI-Timestamp"), h.Get("X-ReachAI-Nonce"), strings.ToLower(h.Get("X-ReachAI-Signature"))
	if key != credential.AppKey || nonce == "" || len(nonce) > 256 {
		return false
	}
	sent, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return false
	}
	now := time.Now().UnixMilli()
	if sent < now-300000 || sent > now+300000 {
		return false
	}
	expected, err := RegistrySignature(c.options.ProjectCode, ts, nonce, credential.AppSecret)
	if err != nil || !hmac.Equal([]byte(expected), []byte(sig)) {
		return false
	}
	ok, err := c.nonces.Claim(ctx, c.options.ProjectCode, nonce, time.UnixMilli(sent+300000))
	return err == nil && ok
}
func respond(w http.ResponseWriter, status int, value any) {
	b, err := json.Marshal(value)
	if err != nil {
		status = http.StatusInternalServerError
		b = []byte(`{"error":"RESPONSE_FAILED"}`)
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(b)
}
func requestJSON(w http.ResponseWriter, r *http.Request, out any) error {
	defer r.Body.Close()
	b, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1024*1024))
	if err != nil {
		return err
	}
	return decodeJSON(b, out)
}
func panicSafe(w http.ResponseWriter) {
	if recover() != nil {
		respond(w, http.StatusInternalServerError, map[string]any{"error": "INTERNAL_ERROR"})
	}
}

// Handler mounts ReachAI's three server-side endpoints into the host router.
func (c *Client) Handler() http.Handler {
	c.mu.Lock()
	c.mounted = true
	c.mu.Unlock()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer panicSafe(w)
		if r.Method != http.MethodPost {
			respond(w, http.StatusMethodNotAllowed, map[string]any{"error": "METHOD_NOT_ALLOWED"})
			return
		}
		switch {
		case r.URL.Path == "/reachai/registry/capabilities/sync":
			if !c.verifyCallback(r.Context(), r.Header) {
				respond(w, http.StatusUnauthorized, map[string]any{"error": "UNAUTHORIZED"})
				return
			}
			result, err := c.Sync(r.Context(), "SDK_CALLBACK")
			if err != nil {
				respond(w, http.StatusBadGateway, map[string]any{"error": "SYNC_FAILED"})
				return
			}
			encoded, err := json.Marshal(result)
			if err != nil {
				respond(w, http.StatusBadGateway, map[string]any{"error": "SYNC_FAILED"})
				return
			}
			c.mu.RLock()
			n := len(c.capabilities)
			c.mu.RUnlock()
			respond(w, http.StatusOK, map[string]any{"instanceId": c.instanceID, "capabilityCount": n, "httpApiCount": 0, "registryResponse": string(encoded)})
		case strings.HasPrefix(r.URL.Path, "/reachai/capabilities/") && strings.HasSuffix(r.URL.Path, "/invoke"):
			name := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/reachai/capabilities/"), "/invoke")
			c.invoke(w, r, name)
		case r.URL.Path == "/reachai/embed/token":
			c.embed(w, r)
		default:
			respond(w, http.StatusNotFound, map[string]any{"error": "NOT_FOUND"})
		}
	})
}

func (c *Client) invoke(w http.ResponseWriter, r *http.Request, name string) {
	c.mu.RLock()
	item := c.capabilities[strings.ToLower(name)]
	credential := c.credential
	c.mu.RUnlock()
	if item == nil || item.definition.Name != name {
		respond(w, http.StatusNotFound, map[string]any{"error": "NOT_FOUND"})
		return
	}
	if credential == nil {
		respond(w, http.StatusUnauthorized, map[string]any{"error": "UNAUTHORIZED"})
		return
	}
	claims, err := VerifyInvocation(r.Header.Get("X-ReachAI-Invocation-Token"), credential.AppKey, credential.AppSecret, c.options.ProjectCode, name, time.Now())
	if err != nil {
		respond(w, http.StatusUnauthorized, map[string]any{"error": "UNAUTHORIZED"})
		return
	}
	str := func(k string) string {
		v, _ := claims[k].(string)
		if strings.TrimSpace(v) == "" {
			return ""
		}
		return v
	}
	identity := Invocation{ProjectCode: c.options.ProjectCode, CapabilityName: name, ExternalUserID: str("externalUserId"), GlobalUserID: str("globalUserId"), TenantID: str("tenantId"), TraceID: str("traceId")}
	if raw, exists := claims["roles"]; exists {
		roles, ok := raw.([]any)
		if !ok {
			respond(w, http.StatusUnauthorized, map[string]any{"error": "UNAUTHORIZED"})
			return
		}
		for _, role := range roles {
			v, ok := role.(string)
			if !ok {
				respond(w, http.StatusUnauthorized, map[string]any{"error": "UNAUTHORIZED"})
				return
			}
			identity.Roles = append(identity.Roles, v)
		}
	}
	if item.definition.BusinessIdentityRequired && identity.ExternalUserID == "" && identity.GlobalUserID == "" {
		respond(w, http.StatusUnauthorized, map[string]any{"error": "UNAUTHORIZED"})
		return
	}
	if len(item.definition.RequiredRoles) > 0 {
		allowed := false
		for _, required := range item.definition.RequiredRoles {
			for _, role := range identity.Roles {
				if role == required {
					allowed = true
				}
			}
		}
		if !allowed {
			respond(w, http.StatusForbidden, map[string]any{"error": "FORBIDDEN"})
			return
		}
	}
	var value any
	if err = requestJSON(w, r, &value); err != nil {
		respond(w, http.StatusBadRequest, map[string]any{"error": "INVALID_ARGUMENTS"})
		return
	}
	if item.definition.BusinessIndexResolver {
		if m, ok := value.(map[string]any); ok {
			if _, exists := m["request"]; !exists {
				value = map[string]any{"request": m}
			}
		}
	}
	if item.input.validate(value) != nil {
		respond(w, http.StatusBadRequest, map[string]any{"error": "INVALID_ARGUMENTS"})
		return
	}
	input := value.(map[string]any)
	var called atomic.Bool
	done := make(chan struct{})
	next := func(ctx context.Context) (any, error) {
		if !called.CompareAndSwap(false, true) {
			return nil, errors.New("capability may only execute once")
		}
		defer close(done)
		nextIdentity := identity
		nextIdentity.Roles = append([]string(nil), identity.Roles...)
		return item.definition.Handler(context.WithValue(ctx, invocationKey{}, nextIdentity), input)
	}
	bridgeIdentity := identity
	bridgeIdentity.Roles = append([]string(nil), identity.Roles...)
	var result any
	if c.deps.SecurityBridge != nil {
		result, err = c.deps.SecurityBridge(r.Context(), bridgeIdentity, next)
	} else {
		result, err = next(r.Context())
	}
	if called.Load() {
		select {
		case <-done:
		case <-r.Context().Done():
			err = r.Context().Err()
		}
	}
	if err != nil || !called.Load() {
		respond(w, http.StatusInternalServerError, map[string]any{"success": false, "code": "CAPABILITY_EXECUTION_FAILED", "message": "业务方法执行失败"})
		return
	}
	if item.definition.BusinessIndexResolver {
		result, err = validateResolution(result, input["request"].(map[string]any), identity)
	} else {
		var plain any
		plain, err = copyJSON(result)
		if err == nil {
			err = item.output.validate(plain)
		}
	}
	if err != nil {
		respond(w, http.StatusInternalServerError, map[string]any{"success": false, "code": "CAPABILITY_EXECUTION_FAILED", "message": "业务方法执行失败"})
		return
	}
	respond(w, http.StatusOK, result)
}

func (c *Client) embed(w http.ResponseWriter, r *http.Request) {
	origin, err := normalizeOrigin(r.Header.Get("Origin"))
	if err != nil || !c.origins[origin] {
		respond(w, http.StatusForbidden, map[string]any{"error": "FORBIDDEN"})
		return
	}
	var body map[string]any
	if requestJSON(w, r, &body) != nil || body == nil {
		respond(w, http.StatusBadRequest, map[string]any{"error": "INVALID_ARGUMENTS"})
		return
	}
	for key := range body {
		switch key {
		case "agentId", "pageKey", "pageInstanceId", "route", "origin":
		default:
			respond(w, http.StatusBadRequest, map[string]any{"error": "INVALID_ARGUMENTS"})
			return
		}
	}
	for _, key := range []string{"pageKey", "route"} {
		if value, exists := body[key]; exists && value != nil {
			if _, ok := value.(string); !ok {
				respond(w, http.StatusBadRequest, map[string]any{"error": "INVALID_ARGUMENTS"})
				return
			}
		}
	}
	text := func(key string) string { v, _ := body[key].(string); return strings.TrimSpace(v) }
	agent, page := text("agentId"), text("pageInstanceId")
	if agent == "" || page == "" || len(agent) > 192 || len(page) > 192 {
		respond(w, http.StatusBadRequest, map[string]any{"error": "INVALID_ARGUMENTS"})
		return
	}
	if raw, ok := body["origin"]; ok && raw != nil {
		value, ok := raw.(string)
		if !ok {
			respond(w, http.StatusForbidden, map[string]any{"error": "FORBIDDEN"})
			return
		}
		expected, e := normalizeOrigin(value)
		if e != nil || expected != origin {
			respond(w, http.StatusForbidden, map[string]any{"error": "FORBIDDEN"})
			return
		}
	}
	if c.deps.PrincipalResolver == nil {
		respond(w, http.StatusServiceUnavailable, map[string]any{"error": "UNAVAILABLE"})
		return
	}
	principal, err := c.deps.PrincipalResolver(r, agent)
	if err != nil {
		respond(w, http.StatusBadGateway, map[string]any{"error": "PRINCIPAL_FAILED"})
		return
	}
	if principal == nil || strings.TrimSpace(principal.ExternalUserID) == "" {
		respond(w, http.StatusUnauthorized, map[string]any{"error": "UNAUTHORIZED"})
		return
	}
	roles := principal.Roles
	if roles == nil {
		roles = []string{}
	}
	attributes := principal.Attributes
	if attributes == nil {
		attributes = map[string]any{}
	}
	var businessSession any
	var expiry any
	if principal.BusinessSessionID != "" {
		businessSession = principal.BusinessSessionID
	}
	if principal.BusinessSessionExpiresAt != 0 {
		expiry = principal.BusinessSessionExpiresAt
	}
	wire := map[string]any{"projectCode": c.options.ProjectCode, "agentId": agent, "pageKey": body["pageKey"], "pageInstanceId": page, "route": body["route"], "origin": origin, "principal": map[string]any{"externalUserId": principal.ExternalUserID, "globalUserId": principal.GlobalUserID, "userName": principal.UserName, "roles": roles, "attributes": attributes}, "businessSessionId": businessSession, "businessSessionExpiresAt": expiry}
	result, err := c.signedPost(r.Context(), "/api/embed/token/exchange", wire, false, true, false)
	if err != nil {
		respond(w, http.StatusBadGateway, map[string]any{"error": "EXCHANGE_FAILED"})
		return
	}
	payload := dataMap(result)
	token, _ := payload["token"].(string)
	seconds, ok := payload["expiresIn"].(json.Number)
	v, e := seconds.Float64()
	if token == "" || !ok || e != nil || v <= 0 || principal.BusinessSessionID != "" && payload["businessSessionBound"] != true || expiry != nil && payload["businessSessionExpiryApplied"] != true {
		respond(w, http.StatusBadGateway, map[string]any{"error": "EXCHANGE_FAILED"})
		return
	}
	respond(w, http.StatusOK, payload)
}

// RevokeBusinessSession confirms server-side revocation; hosts must retry failures reliably.
func (c *Client) RevokeBusinessSession(ctx context.Context, id string) error {
	if strings.TrimSpace(id) == "" {
		return errors.New("business session required")
	}
	result, err := c.signedPost(ctx, "/api/embed/token/business-session/revoke", map[string]any{"projectCode": c.options.ProjectCode, "businessSessionId": id}, false, true, false)
	if err != nil {
		return err
	}
	if fmt.Sprint(result["code"]) != "200" {
		return errors.New("business session revocation not confirmed")
	}
	return nil
}
