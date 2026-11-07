package reachai

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Credentials are never rendered with their secret by fmt or JSON.
type Credentials struct {
	AppKey    string
	AppSecret string
}

func (c Credentials) String() string {
	return fmt.Sprintf("Credentials{AppKey:%q, AppSecret:[REDACTED]}", c.AppKey)
}
func (c Credentials) GoString() string { return c.String() }
func (c Credentials) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]any{"appKey": c.AppKey, "appSecret": "[REDACTED]"})
}
func (c Credentials) valid() bool {
	return strings.TrimSpace(c.AppKey) != "" && strings.TrimSpace(c.AppSecret) != ""
}

// CredentialStore is durable and must fail closed after an uncertain enrollment.
type CredentialStore interface {
	Load() (*Credentials, error)
	Save(Credentials) error
	EnrollmentUncertain() (bool, error)
	MarkEnrollmentStarted() error
	ClearEnrollmentStarted() error
}

// EncryptedFileCredentialStore stores AES-256-GCM ciphertext in a host-protected directory.
// The caller must independently store the 32-byte key and restrict directory ACLs.
type EncryptedFileCredentialStore struct {
	path string
	key  []byte
}

func NewEncryptedFileCredentialStore(path string, key []byte) (*EncryptedFileCredentialStore, error) {
	if strings.TrimSpace(path) == "" || len(key) != 32 {
		return nil, errors.New("credential path and 32-byte key required")
	}
	return &EncryptedFileCredentialStore{path: path, key: append([]byte(nil), key...)}, nil
}
func (s *EncryptedFileCredentialStore) String() string {
	return "EncryptedFileCredentialStore{[REDACTED]}"
}
func (s *EncryptedFileCredentialStore) GoString() string { return s.String() }
func (s *EncryptedFileCredentialStore) MarshalJSON() ([]byte, error) {
	return []byte(`"[REDACTED]"`), nil
}
func randomBytes(n int) ([]byte, error) {
	b := make([]byte, n)
	_, err := io.ReadFull(rand.Reader, b)
	return b, err
}
func (s *EncryptedFileCredentialStore) aead() (cipher.AEAD, error) {
	block, err := aes.NewCipher(s.key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}
func (s *EncryptedFileCredentialStore) Load() (*Credentials, error) {
	data, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	a, err := s.aead()
	if err != nil {
		return nil, err
	}
	if len(data) < 1+a.NonceSize()+a.Overhead() || data[0] != 1 {
		return nil, errors.New("invalid credential file")
	}
	plain, err := a.Open(nil, data[1:1+a.NonceSize()], data[1+a.NonceSize():], nil)
	if err != nil {
		return nil, errors.New("unable to decrypt credential file")
	}
	var raw struct {
		AppKey    string `json:"appKey"`
		AppSecret string `json:"appSecret"`
	}
	if json.Unmarshal(plain, &raw) != nil {
		return nil, errors.New("invalid credential data")
	}
	c := Credentials{raw.AppKey, raw.AppSecret}
	if !c.valid() {
		return nil, errors.New("invalid credential data")
	}
	return &c, nil
}
func (s *EncryptedFileCredentialStore) Save(c Credentials) error {
	if !c.valid() {
		return errors.New("project credentials required")
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0700); err != nil {
		return err
	}
	a, err := s.aead()
	if err != nil {
		return err
	}
	nonce, err := randomBytes(a.NonceSize())
	if err != nil {
		return err
	}
	plain, err := json.Marshal(struct {
		AppKey    string `json:"appKey"`
		AppSecret string `json:"appSecret"`
	}{c.AppKey, c.AppSecret})
	if err != nil {
		return err
	}
	data := append([]byte{1}, nonce...)
	data = a.Seal(data, nonce, plain, nil)
	tmp, err := os.CreateTemp(filepath.Dir(s.path), ".reachai-credential-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if err = tmp.Chmod(0600); err == nil {
		_, err = tmp.Write(data)
	}
	if err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	// Hard-link atomically creates only if the credential path does not exist.
	return os.Link(name, s.path)
}
func (s *EncryptedFileCredentialStore) EnrollmentUncertain() (bool, error) {
	_, err := os.Stat(s.path + ".enrollment-pending")
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	return err == nil, err
}
func (s *EncryptedFileCredentialStore) MarkEnrollmentStarted() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0700); err != nil {
		return err
	}
	f, err := os.OpenFile(s.path+".enrollment-pending", os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	_, err = f.WriteString("Enrollment outcome may be uncertain; verify before retrying.\n")
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err == nil {
		return closeErr
	}
	return err
}
func (s *EncryptedFileCredentialStore) ClearEnrollmentStarted() error {
	err := os.Remove(s.path + ".enrollment-pending")
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}
