package reachai

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf16"
)

// IndexRowScope is created only after the host has checked actual row visibility.
type IndexRowScope struct {
	tenant, user, project, resourceType, resourceID string
	proof                                           *indexProof
}
type indexProof struct{}

var authorizedIndexProof = &indexProof{}

// IndexRowAuthorization contains trusted request and authoritative business-row evidence.
type IndexRowAuthorization struct {
	InvocationTenantID, AuthoritativeRowTenantID, CurrentUserID, ProjectCode, ResourceType, RequestedResourceID, AuthoritativeResourceID string
	VisibleToCurrentUser                                                                                                                 bool
}

func validIndexText(v string, max int, identifier bool) bool {
	v = strings.TrimSpace(v)
	return v != "" && len(utf16.Encode([]rune(v))) <= max && (!identifier || indexIdentity.MatchString(v))
}

// AuthorizeIndexRow requires the host's own row-permission decision; it cannot perform it for the host.
func AuthorizeIndexRow(a IndexRowAuthorization) (*IndexRowScope, error) {
	tenant, actualTenant, user, project, kind, requested, actual := strings.TrimSpace(a.InvocationTenantID), strings.TrimSpace(a.AuthoritativeRowTenantID), strings.TrimSpace(a.CurrentUserID), strings.TrimSpace(a.ProjectCode), strings.TrimSpace(a.ResourceType), strings.TrimSpace(a.RequestedResourceID), strings.TrimSpace(a.AuthoritativeResourceID)
	if !validIndexText(tenant, 96, true) || !validIndexText(actualTenant, 96, true) || !validIndexText(user, 128, false) || !validIndexText(project, 96, true) || !validIndexText(kind, 96, true) || !validIndexText(requested, 256, false) || !validIndexText(actual, 256, false) || tenant != actualTenant || requested != actual || !a.VisibleToCurrentUser {
		return nil, errors.New("business row authorization failed")
	}
	return &IndexRowScope{tenant, user, project, kind, actual, authorizedIndexProof}, nil
}

type IndexResolution struct {
	scope                       *IndexRowScope
	sourceSystem, sourceVersion string
	data                        map[string]any
	resolvedAt                  string
	proof                       *indexProof
}

// NewIndexResolution produces a typed resolution after the host authorizes a specific row.
func NewIndexResolution(scope *IndexRowScope, sourceSystem, sourceVersion string, data map[string]any) (*IndexResolution, error) {
	if scope == nil || scope.proof != authorizedIndexProof || !validIndexText(sourceSystem, 96, true) || !validIndexText(sourceVersion, 128, false) || data == nil {
		return nil, errors.New("authorized row scope required")
	}
	copy, err := copyJSON(data)
	if err != nil {
		return nil, err
	}
	m, ok := copy.(map[string]any)
	if !ok {
		return nil, errors.New("invalid business data")
	}
	return &IndexResolution{scope, strings.TrimSpace(sourceSystem), strings.TrimSpace(sourceVersion), m, time.Now().UTC().Format(time.RFC3339Nano), authorizedIndexProof}, nil
}
func validateIndexSchema(s *schema) error {
	if s.kind != "object" || len(s.properties) != 1 || !s.required["request"] || len(s.required) != 1 {
		return errors.New("index resolver requires request object")
	}
	req := s.properties["request"]
	if req == nil || req.kind != "object" || req.nullable || len(req.properties) != 3 || len(req.required) != 3 {
		return errors.New("invalid index resolver request")
	}
	for _, k := range []string{"resourceType", "resourceId", "expectedSourceVersion"} {
		p := req.properties[k]
		if p == nil || p.kind != "string" || p.nullable || !req.required[k] {
			return errors.New("invalid index resolver request")
		}
	}
	return nil
}
func validateResolution(value any, request map[string]any, identity Invocation) (map[string]any, error) {
	r, ok := value.(*IndexResolution)
	if !ok || r == nil || r.proof != authorizedIndexProof || r.scope == nil || r.scope.proof != authorizedIndexProof {
		return nil, errors.New("authorized index result required")
	}
	typeName, _ := request["resourceType"].(string)
	id, _ := request["resourceId"].(string)
	expected, _ := request["expectedSourceVersion"].(string)
	user := identity.ExternalUserID
	if user == "" {
		user = identity.GlobalUserID
	}
	if !validIndexText(identity.TenantID, 96, true) || !validIndexText(user, 128, false) || !validIndexText(typeName, 96, true) || !validIndexText(id, 256, false) || !validIndexText(expected, 128, false) || r.scope.tenant != strings.TrimSpace(identity.TenantID) || r.scope.user != strings.TrimSpace(user) || r.scope.project != identity.ProjectCode || r.scope.resourceType != strings.TrimSpace(typeName) || r.scope.resourceID != strings.TrimSpace(id) {
		return nil, errors.New("index scope mismatch")
	}
	return map[string]any{"schema": "reachai-business-index-resolution-v1", "tenantId": r.scope.tenant, "projectCode": r.scope.project, "sourceSystem": r.sourceSystem, "resourceType": r.scope.resourceType, "resourceId": r.scope.resourceID, "sourceVersion": r.sourceVersion, "data": r.data, "resolvedAt": r.resolvedAt}, nil
}
func (c *Client) indexPath(code, operation string) (string, error) {
	if !indexName.MatchString(code) {
		return "", errors.New("invalid business index")
	}
	return fmt.Sprintf("/api/knowledge-ingress/projects/%s/biz-index/%s/%s", c.options.ProjectCode, code, operation), nil
}

// IndexUpsert, IndexBatch and IndexDelete sign exact request bytes and do not retry writes.
func (c *Client) IndexUpsert(ctx context.Context, code string, record map[string]any) (map[string]any, error) {
	p, e := c.indexPath(code, "upsert")
	if e != nil {
		return nil, e
	}
	return c.signedPost(ctx, p, record, false, true, true)
}
func (c *Client) IndexBatch(ctx context.Context, code string, records []map[string]any) (map[string]any, error) {
	p, e := c.indexPath(code, "batch")
	if e != nil {
		return nil, e
	}
	return c.signedPost(ctx, p, map[string]any{"items": records}, false, true, true)
}
func (c *Client) IndexDelete(ctx context.Context, code, id string) (map[string]any, error) {
	p, e := c.indexPath(code, "delete")
	if e != nil {
		return nil, e
	}
	return c.signedPost(ctx, p, map[string]any{"bizId": id}, false, true, true)
}
