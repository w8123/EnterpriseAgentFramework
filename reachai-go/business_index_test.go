package reachai

import (
	"context"
	"net/http/httptest"
	"testing"
	"time"
)

func TestIndexResolverGuardsUserTenantAndResource(t *testing.T) {
	c := mustClient(t, Options{RegistryURL: "http://registry", ProjectCode: "demo", BaseURL: "http://host", AppKey: "key", AppSecret: "secret"}, Dependencies{SecurityBridge: func(ctx context.Context, id Invocation, next func(context.Context) (any, error)) (any, error) {
		return next(ctx)
	}})
	request := obj(map[string]any{"resourceType": scalar("string"), "resourceId": scalar("string"), "expectedSourceVersion": scalar("string")}, "resourceType", "resourceId", "expectedSourceVersion")
	input := obj(map[string]any{"request": request}, "request")
	output := obj(map[string]any{})
	err := c.RegisterCapability(Capability{Name: "order.resolve", SideEffect: "READ", BusinessIdentityRequired: true, BusinessIndexResolver: true, InputSchema: input, OutputSchema: output, Handler: func(ctx context.Context, args map[string]any) (any, error) {
		id, _ := CurrentInvocation(ctx)
		req := args["request"].(map[string]any)
		scope, e := AuthorizeIndexRow(IndexRowAuthorization{InvocationTenantID: id.TenantID, AuthoritativeRowTenantID: "tenant1", CurrentUserID: id.ExternalUserID, ProjectCode: id.ProjectCode, ResourceType: req["resourceType"].(string), RequestedResourceID: req["resourceId"].(string), AuthoritativeResourceID: "42", VisibleToCurrentUser: id.ExternalUserID == "alice"})
		if e != nil {
			return nil, e
		}
		return NewIndexResolution(scope, "orders", "v1", map[string]any{"name": "demo"})
	}})
	if err != nil {
		t.Fatal(err)
	}
	app := httptest.NewServer(c.Handler())
	defer app.Close()
	requestBody := map[string]any{"resourceType": "order", "resourceId": "42", "expectedSourceVersion": "v1"}
	for _, tc := range []struct {
		user, tenant string
		body         any
		expected     int
	}{{"alice", "tenant1", requestBody, 200}, {"alice", "tenant1", map[string]any{"request": requestBody}, 200}, {"bob", "tenant1", requestBody, 500}, {"alice", "other", requestBody, 500}, {"alice", "tenant1", map[string]any{"resourceType": "order", "resourceId": "43", "expectedSourceVersion": "v1"}, 500}, {"alice", "tenant1", map[string]any{"resourceType": "order", "resourceId": "42", "expectedSourceVersion": "v1", "extra": true}, 400}} {
		token := signedTestToken(t, "secret", map[string]any{"alg": "HS256"}, map[string]any{"projectCode": "demo", "appKey": "key", "capabilityName": "order.resolve", "exp": time.Now().Unix() + 60, "externalUserId": tc.user, "tenantId": tc.tenant, "roles": []string{}})
		status, result := mustRequest(t, app.Client(), app.URL+"/reachai/capabilities/order.resolve/invoke", tc.body, map[string]string{"X-ReachAI-Invocation-Token": token})
		if status != tc.expected {
			t.Fatalf("user %s tenant %s: status %d", tc.user, tc.tenant, status)
		}
		if status == 200 && (result["resourceId"] != "42" || result["tenantId"] != "tenant1") {
			t.Fatal("invalid index response")
		}
	}
	if _, err := NewIndexResolution(&IndexRowScope{}, "orders", "v1", map[string]any{}); err == nil {
		t.Fatal("forged scope accepted")
	}
}
