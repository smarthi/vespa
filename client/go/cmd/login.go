// Package cmd Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa login command
// Author: ldalves
package cmd

import (
	"context"
	"fmt"

	"github.com/pkg/browser"
	"github.com/spf13/cobra"
	"github.com/vespa-engine/vespa/client/go/auth"
)

func init() {
	rootCmd.AddCommand(loginCmd)
}

var loginCmd = &cobra.Command{
	Use:               "login",
	Short:             "Request a device code for authentication with Vespa Cloud",
	Example:           "$ vespa login",
	DisableAutoGenTag: true,
	Args:              cobra.ExactArgs(0),
	Run: func(cmd *cobra.Command, args []string) {
		ctx := cmd.Context()
		err := RunLogin(ctx)
		if err != nil {
			fmt.Println(err)
		}
	},
}

// RunLogin runs the login flow guiding the user through the process
// by showing the login instructions, opening the browser.
// Use `expired` to run the login from other commands setup:
// this will only affect the messages.
func RunLogin(ctx context.Context) error {
	identity := &auth.Identity{
		Authenticator: &auth.Authenticator{
			Audience:           "https://vespa-cd.auth0.com/api/v2/",
			ClientID:           "4wYWA496zBP28SLiz0PuvCt8ltL11DZX",
			DeviceCodeEndpoint: "https://vespa-cd.auth0.com/oauth/device/code",
			OauthTokenEndpoint: "https://vespa-cd.auth0.com/oauth/token",
		},
	}

	state, err := identity.Authenticator.Start(ctx)
	if err != nil {
		return fmt.Errorf("could not start the authentication process: %w", err)
	}

	fmt.Printf("Your Device Confirmation code is: %s\n\n", state.UserCode)
	fmt.Println("Press Enter to open the browser to log in or ^C to quit...")

	fmt.Scanln()
	err = browser.OpenURL(state.VerificationURI)
	if err != nil {
		fmt.Printf("Couldn't open the URL, please do it manually: %s.", state.VerificationURI)
	}

	var res auth.Result
	err = auth.Spinner("Waiting for login to complete in browser", func() error {
		res, err = identity.Authenticator.Wait(ctx, state)
		return err
	})

	if err != nil {
		fmt.Errorf("login error: %w", err)
	}

	fmt.Print("\n")
	fmt.Println("Successfully logged in.")

	fmt.Print("\n")
	fmt.Println(res)

	return nil
}
