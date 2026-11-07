package reachai

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func signedTestToken(t *testing.T, secret string, header, claims any) string {
	t.Helper()
	h, e := json.Marshal(header)
	if e != nil {
		t.Fatal(e)
	}
	b, e := json.Marshal(claims)
	if e != nil {
		t.Fatal(e)
	}
	value := base64.RawURLEncoding.EncodeToString(h) + "." + base64.RawURLEncoding.EncodeToString(b)
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(value))
	return value + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}
func TestSharedProtocolVectors(t *testing.T) {
	b, err := os.ReadFile(filepath.Join("..", "ai-runtime-contract", "src", "main", "resources", "contracts", "reachai-sdk-protocol-v1.vectors.json"))
	if err != nil {
		t.Fatal(err)
	}
	var vectors struct {
		Registry       []struct{ ProjectCode, Timestamp, Nonce, AppSecret, Signature string }                             `json:"registry"`
		ProjectRequest []struct{ Method, Path, ProjectCode, AppKey, Timestamp, Nonce, Body, AppSecret, Signature string } `json:"projectRequest"`
		Invocation     []struct {
			AppSecret, Token string
			Claims           struct {
				ProjectCode, AppKey, CapabilityName, ExternalUserID string
				Iat, Exp                                            int64
			} `json:"claims"`
		} `json:"invocation"`
	}
	if err = json.Unmarshal(b, &vectors); err != nil {
		t.Fatal(err)
	}
	for _, v := range vectors.Registry {
		got, err := RegistrySignature(v.ProjectCode, v.Timestamp, v.Nonce, v.AppSecret)
		if err != nil || got != v.Signature {
			t.Fatalf("registry signature mismatch: %v", err)
		}
	}
	for _, v := range vectors.ProjectRequest {
		got, err := BodySignature(v.Method, v.Path, v.ProjectCode, v.AppKey, v.Timestamp, v.Nonce, []byte(v.Body), v.AppSecret)
		if err != nil || got != v.Signature {
			t.Fatalf("body signature mismatch: %v", err)
		}
	}
	for _, v := range vectors.Invocation {
		got, err := VerifyInvocation(v.Token, v.Claims.AppKey, v.AppSecret, v.Claims.ProjectCode, v.Claims.CapabilityName, time.Unix(v.Claims.Iat, 0))
		if err != nil || got["externalUserId"] != v.Claims.ExternalUserID {
			t.Fatalf("invocation vector failed: %v", err)
		}
		for _, scope := range []struct {
			key, project, cap string
			now               int64
		}{{"wrong", v.Claims.ProjectCode, v.Claims.CapabilityName, v.Claims.Iat}, {v.Claims.AppKey, "wrong", v.Claims.CapabilityName, v.Claims.Iat}, {v.Claims.AppKey, v.Claims.ProjectCode, "wrong", v.Claims.Iat}, {v.Claims.AppKey, v.Claims.ProjectCode, v.Claims.CapabilityName, v.Claims.Exp}} {
			if _, err = VerifyInvocation(v.Token, scope.key, v.AppSecret, scope.project, scope.cap, time.Unix(scope.now, 0)); err == nil {
				t.Fatal("invalid scope/time accepted")
			}
		}
	}
	for _, p := range []string{"x", "/a?x=1", "/x#y", "/x/../y", "/x\nY"} {
		if _, err = BodySignature("POST", p, "p", "key", "1", "n", nil, "s"); err == nil {
			t.Fatal("ambiguous path accepted")
		}
	}
}
func TestInvocationRejectsInvalidClaimsAndTrailingPayload(t *testing.T) {
	now := time.Now().Unix()
	base := map[string]any{"projectCode": "demo", "appKey": "key", "capabilityName": "read", "exp": now + 30, "iat": now}
	for _, edit := range []func(map[string]any){func(m map[string]any) { m["exp"] = "bad" }, func(m map[string]any) { m["nbf"] = now + 1 }, func(m map[string]any) { m["iat"] = now + 1 }, func(m map[string]any) { delete(m, "exp") }} {
		m := map[string]any{}
		for k, v := range base {
			m[k] = v
		}
		edit(m)
		token := signedTestToken(t, "secret", map[string]any{"alg": "HS256"}, m)
		if _, err := VerifyInvocation(token, "key", "secret", "demo", "read", time.Unix(now, 0)); err == nil {
			t.Fatal("invalid time accepted")
		}
	}
	badAlg := signedTestToken(t, "secret", map[string]any{"alg": "none"}, base)
	if _, err := VerifyInvocation(badAlg, "key", "secret", "demo", "read", time.Unix(now, 0)); err == nil {
		t.Fatal("invalid algorithm accepted")
	}
	valid := signedTestToken(t, "secret", map[string]any{"alg": "HS256"}, base)
	parts := strings.Split(valid, ".")
	body, _ := base64.RawURLEncoding.DecodeString(parts[1])
	parts[1] = base64.RawURLEncoding.EncodeToString(append(body, []byte(" true")...))
	in := parts[0] + "." + parts[1]
	mac := hmac.New(sha256.New, []byte("secret"))
	_, _ = mac.Write([]byte(in))
	bad := in + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
	if _, err := VerifyInvocation(bad, "key", "secret", "demo", "read", time.Unix(now, 0)); err == nil {
		t.Fatal("trailing payload accepted")
	}
}
