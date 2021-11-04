package auth

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/ioutil"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type config struct {
	DefaultTenant string            `json:"default_tenant"`
	Tenants       map[string]Tenant `json:"tenants"`
}

// Tenant in Auth0
type Tenant struct {
	Name         string    `json:"name"`
	Domain       string    `json:"domain"`
	AccessToken  string    `json:"access_token,omitempty"`
	Scopes       []string  `json:"scopes,omitempty"`
	ExpiresAt    time.Time `json:"expires_at"`
	ClientID     string    `json:"client_id"`
	ClientSecret string    `json:"client_secret"`
}

type Identity struct {
	Authenticator *Authenticator
	initOnce      sync.Once
	errOnce       error
	Path          string
	Config        config
	tenant        string
}

var errUnauthenticated = errors.New("not logged in. try 'vespa login'")

// AddTenant assigns an existing, or new tenant. This is expected to be called
// after a login has completed.
func (i *Identity) AddTenant(t Tenant) error {
	// init will fail here with a `no tenant found` error if we're logging
	// in for the first time and that's expected.
	_ = i.init()

	// If there's no existing DefaultTenant yet, might as well set the
	// first successfully logged in tenant during onboard.
	if i.Config.DefaultTenant == "" {
		i.Config.DefaultTenant = t.Domain
	}

	// If we're dealing with an empty file, we'll need to initialize this
	// map.
	if i.Config.Tenants == nil {
		i.Config.Tenants = map[string]Tenant{}
	}

	i.Config.Tenants[t.Domain] = t

	if err := i.PersistConfig(); err != nil {
		return fmt.Errorf("unexpected error persisting config: %w", err)
	}

	return nil
}

func (i *Identity) PersistConfig() error {
	dir := filepath.Dir(i.Path)
	if _, err := os.Stat(dir); os.IsNotExist(err) {
		if err := os.MkdirAll(dir, 0700); err != nil {
			return err
		}
	}

	buf, err := json.MarshalIndent(i.Config, "", "    ")
	if err != nil {
		return err
	}

	if err := ioutil.WriteFile(i.Path, buf, 0600); err != nil {
		return err
	}
	return nil
}

func (i *Identity) init() error {
	i.initOnce.Do(func() {
		// Initialize the context -- e.g. the configuration
		// information, tenants, etc.
		if i.errOnce = i.initContext(); i.errOnce != nil {
			return
		}
	})
	// Once initialized, we'll keep returning the same err that was
	// originally encountered.
	return i.errOnce
}

func (i *Identity) initContext() (err error) {
	if _, err := os.Stat(i.Path); os.IsNotExist(err) {
		return errUnauthenticated
	}

	var buf []byte
	if buf, err = ioutil.ReadFile(i.Path); err != nil {
		return err
	}

	if err := json.Unmarshal(buf, &i.Config); err != nil {
		return err
	}

	if i.tenant == "" && i.Config.DefaultTenant == "" {
		return errUnauthenticated
	}

	if i.tenant == "" {
		i.tenant = i.Config.DefaultTenant
	}

	return nil
}
