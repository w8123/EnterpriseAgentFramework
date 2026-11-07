// Package reachai integrates trusted Go business services with ReachAI.
package reachai

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

func field(s string) (string, error) {
	s = strings.TrimSpace(s)
	if s == "" || strings.ContainsAny(s, "\r\n") {
		return "", errors.New("invalid signature field")
	}
	return s, nil
}

func sign(secret, message string) (string, error) {
	s, err := field(secret)
	if err != nil {
		return "", err
	}
	m := hmac.New(sha256.New, []byte(s))
	_, _ = m.Write([]byte(message))
	return hex.EncodeToString(m.Sum(nil)), nil
}

// RegistrySignature computes the existing registry protocol signature.
func RegistrySignature(projectCode, timestamp, nonce, secret string) (string, error) {
	values := []string{projectCode, timestamp, nonce}
	for i := range values {
		var err error
		values[i], err = field(values[i])
		if err != nil {
			return "", err
		}
	}
	return sign(secret, strings.Join(values, "\n"))
}

// BodySignature signs the exact bytes sent on a body-bound project request.
func BodySignature(method, path, projectCode, appKey, timestamp, nonce string, body []byte, secret string) (string, error) {
	var err error
	path, err = field(path)
	if err != nil || !strings.HasPrefix(path, "/") || strings.ContainsAny(path, "?#") || strings.Contains(path, "..") {
		return "", errors.New("invalid signed path")
	}
	values := []string{method, projectCode, appKey, timestamp, nonce}
	for i := range values {
		values[i], err = field(values[i])
		if err != nil {
			return "", err
		}
	}
	digest := sha256.Sum256(body)
	return sign(secret, strings.Join([]string{"REACHAI_PROJECT_REQUEST_V1", strings.ToUpper(values[0]), path, values[1], values[2], values[3], values[4], hex.EncodeToString(digest[:])}, "\n"))
}

// VerifyInvocation verifies an HS256 invocation token and its complete target scope.
func VerifyInvocation(token, appKey, secret, projectCode, capability string, now time.Time) (map[string]any, error) {
	invalid := errors.New("invalid invocation token")
	if len(token) > 16384 {
		return nil, invalid
	}
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, invalid
	}
	for _, p := range parts {
		if p == "" || strings.ContainsAny(p, "=+/") {
			return nil, invalid
		}
	}
	key, err := field(secret)
	if err != nil {
		return nil, invalid
	}
	mac := hmac.New(sha256.New, []byte(key))
	_, _ = mac.Write([]byte(parts[0] + "." + parts[1]))
	sig, err := base64.RawURLEncoding.Strict().DecodeString(parts[2])
	if err != nil || !hmac.Equal(sig, mac.Sum(nil)) {
		return nil, invalid
	}
	head, err := base64.RawURLEncoding.Strict().DecodeString(parts[0])
	if err != nil {
		return nil, invalid
	}
	body, err := base64.RawURLEncoding.Strict().DecodeString(parts[1])
	if err != nil {
		return nil, invalid
	}
	var hdr map[string]any
	if json.Unmarshal(head, &hdr) != nil || hdr["alg"] != "HS256" {
		return nil, invalid
	}
	var claims map[string]any
	if decodeJSON(body, &claims) != nil || claims == nil {
		return nil, invalid
	}
	readTime := func(k string, required bool) (int64, error) {
		x, ok := claims[k]
		if !ok && !required {
			return 0, nil
		}
		n, ok := x.(json.Number)
		if !ok {
			return 0, invalid
		}
		return strconv.ParseInt(string(n), 10, 64)
	}
	exp, err := readTime("exp", true)
	if err != nil || exp <= now.Unix() {
		return nil, invalid
	}
	for _, k := range []string{"iat", "nbf"} {
		v, e := readTime(k, false)
		if e != nil || v > now.Unix() {
			return nil, invalid
		}
	}
	for k, v := range map[string]string{"projectCode": projectCode, "appKey": appKey, "capabilityName": capability} {
		if claims[k] != v {
			return nil, invalid
		}
	}
	return claims, nil
}

func uuid() (string, error) {
	b, err := randomBytes(16)
	if err != nil {
		return "", err
	}
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:]), nil
}
