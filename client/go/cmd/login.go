// Package cmd Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa login command
// Author: ldalves
package cmd

import (
	"fmt"
	"time"

	"github.com/joeshaw/envdecode"
	"github.com/pkg/browser"
	"github.com/spf13/cobra"
	"github.com/vespa-engine/vespa/client/go/auth"
	"github.com/vespa-engine/vespa/client/go/util"
)

func init() {
	rootCmd.AddCommand(loginCmd)
}

// default to vespa-cd.auth0.com
var authCfg struct {
	Audience           string `env:"AUTH0_AUDIENCE,default=https://vespa-cd.auth0.com/api/v2/"`
	ClientID           string `env:"AUTH0_CLIENT_ID,default=4wYWA496zBP28SLiz0PuvCt8ltL11DZX"`
	DeviceCodeEndpoint string `env:"AUTH0_DEVICE_CODE_ENDPOINT,default=https://vespa-cd.auth0.com/oauth/device/code"`
	OauthTokenEndpoint string `env:"AUTH0_OAUTH_TOKEN_ENDPOINT,default=https://vespa-cd.auth0.com/oauth/token"`
}

var loginCmd = &cobra.Command{
	Use:               "login",
	Short:             "Request a device code for authentication with Vespa Cloud",
	Example:           "$ vespa login",
	DisableAutoGenTag: true,
	Args:              cobra.ExactArgs(0),
	Run: func(cmd *cobra.Command, args []string) {
		cfg, err := LoadConfig()
		if err != nil {
			fatalErr(err, "Could not load config")
			return
		}

		if err := envdecode.StrictDecode(&authCfg); err != nil {
			fmt.Println(fmt.Errorf("could not decode env: %w", err))
		}

		identity := &auth.Identity{}

		if identity.Path == "" {
			identity.Path = cfg.AuthConfigPath()
		}

		identity.Authenticator = &auth.Authenticator{
			Audience:           authCfg.Audience,
			ClientID:           authCfg.ClientID,
			DeviceCodeEndpoint: authCfg.DeviceCodeEndpoint,
			OauthTokenEndpoint: authCfg.OauthTokenEndpoint,
		}

		state, err := identity.Authenticator.Start()
		if err != nil {
			fmt.Println(fmt.Errorf("could not start the authentication process: %w", err))
		}

		fmt.Printf("Your Device Confirmation code is: %s\n\n", state.UserCode)
		fmt.Println("Press Enter to open the browser to log in or ^C to quit...")

		fmt.Scanln()
		err = browser.OpenURL(state.VerificationURI)
		if err != nil {
			fmt.Printf("Couldn't open the URL, please do it manually: %s.", state.VerificationURI)
		}

		var res auth.Result
		err = util.Spinner("Waiting for login to complete in browser", func() error {
			res, err = identity.Authenticator.Wait(cmd.Context(), state)
			return err
		})
		if err != nil {
			fmt.Println(fmt.Errorf("login error: %w", err))
		}

		fmt.Print("\n")
		fmt.Println("Successfully logged in.")

		// store the refresh token
		secretsStore := &auth.Keyring{}
		err = secretsStore.Set(auth.SecretsNamespace, res.Domain, res.RefreshToken)
		if err != nil {
			// log the error but move on
			fmt.Println("Could not store the refresh token locally, please expect to login again once your access token expired.")
		}

		t := auth.Tenant{
			Name:        res.Tenant,
			Domain:      res.Domain,
			AccessToken: res.AccessToken,
			ExpiresAt:   time.Now().Add(time.Duration(res.ExpiresIn) * time.Second),
			Scopes:      auth.RequiredScopes(),
		}

		err = identity.AddTenant(t)
		if err != nil {
			fmt.Println(fmt.Errorf("could not add tenant to config: %w", err))
		}

		identity.Config.DefaultTenant = res.Domain

		if err := identity.PersistConfig(); err != nil {
			fmt.Printf("Could not set the default tenant, please try 'vesta tenants use %s': %w", res.Domain, err)
		}

		fmt.Print("\n")
		fmt.Println(res)
	},
}
