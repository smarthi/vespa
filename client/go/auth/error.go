// Package auth Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa login command
// Author: ldalves
package auth

import "github.com/pkg/errors"

func Error(e error, message string) error {
	return errors.Wrap(e, message)
}
