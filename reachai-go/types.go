package reachai

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

// Options configure an individual project integration. Secrets are excluded from formatted output.
type Options struct {
	RegistryURL       string
	ProjectCode       string
	ProjectName       string
	BaseURL           string
	ContextPath       string
	Environment       string
	InstanceID        string
	EnrollmentToken   string
	AppKey            string
	AppSecret         string
	HeartbeatInterval time.Duration
	AllowedOrigins    []string
}

func (o Options) String() string {
	return fmt.Sprintf("Options{ProjectCode:%q, BaseURL:%q, secrets:[REDACTED]}", o.ProjectCode, o.BaseURL)
}
func (o Options) GoString() string { return o.String() }
func (o Options) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]any{"projectCode": o.ProjectCode, "baseUrl": o.BaseURL, "secrets": "[REDACTED]"})
}

// Invocation is made exclusively from the verified platform token.
type Invocation struct {
	ProjectCode    string
	CapabilityName string
	ExternalUserID string
	GlobalUserID   string
	TenantID       string
	Roles          []string
	TraceID        string
}
type invocationKey struct{}

// CurrentInvocation returns a defensive copy of the request-scoped identity.
func CurrentInvocation(ctx context.Context) (Invocation, bool) {
	v, ok := ctx.Value(invocationKey{}).(Invocation)
	v.Roles = append([]string(nil), v.Roles...)
	return v, ok
}

type HandlerFunc func(context.Context, map[string]any) (any, error)
type SecurityBridge func(context.Context, Invocation, func(context.Context) (any, error)) (any, error)

// EmbedPrincipal must come from a verified business session, never browser input.
type EmbedPrincipal struct {
	ExternalUserID           string
	GlobalUserID             string
	UserName                 string
	Roles                    []string
	Attributes               map[string]any
	BusinessSessionID        string
	BusinessSessionExpiresAt int64
}

func (p EmbedPrincipal) String() string               { return "EmbedPrincipal{[REDACTED]}" }
func (p EmbedPrincipal) GoString() string             { return p.String() }
func (p EmbedPrincipal) MarshalJSON() ([]byte, error) { return []byte(`"[REDACTED]"`), nil }

type PrincipalResolver func(*http.Request, string) (*EmbedPrincipal, error)
type CallbackNonceStore interface {
	Claim(context.Context, string, string, time.Time) (bool, error)
}
type Dependencies struct {
	CredentialStore   CredentialStore
	PrincipalResolver PrincipalResolver
	SecurityBridge    SecurityBridge
	NonceStore        CallbackNonceStore
	HTTPClient        *http.Client
}

func (d Dependencies) String() string               { return "Dependencies{[REDACTED]}" }
func (d Dependencies) GoString() string             { return d.String() }
func (d Dependencies) MarshalJSON() ([]byte, error) { return []byte(`"[REDACTED]"`), nil }

// Capability describes a complete, explicit business method and its JSON schemas.
type Capability struct {
	Name                     string
	Title                    string
	Description              string
	SideEffect               string
	BusinessIdentityRequired bool
	RequiredRoles            []string
	InputSchema              map[string]any
	OutputSchema             map[string]any
	BusinessIndexResolver    bool
	Handler                  HandlerFunc
}
