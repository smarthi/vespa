// Package cmd Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa login command
// Author: ldalves

package auth

import (
	"time"

	"github.com/briandowns/spinner"
)

const (
	spinnerTextEllipsis = "..."
	spinnerTextDone     = "done"
	spinnerTextFailed   = "failed"
	spinnerColor        = "blue"
)

func Waiting(fn func() error) error {
	return loading("", "", "", fn)
}

func Spinner(text string, fn func() error) error {
	initialMsg := text + spinnerTextEllipsis + " "
	doneMsg := initialMsg + spinnerTextDone + "\n"
	failMsg := initialMsg + spinnerTextFailed + "\n"

	return loading(initialMsg, doneMsg, failMsg, fn)
}

func loading(initialMsg, doneMsg, failMsg string, fn func() error) error {
	done := make(chan struct{})
	errc := make(chan error)
	go func() {
		defer close(done)

		s := spinner.New(spinner.CharSets[11], 100*time.Millisecond, spinner.WithWriter(Messages))
		s.Prefix = initialMsg
		s.FinalMSG = doneMsg
		s.HideCursor = true
		s.Writer = Messages

		if err := s.Color(spinnerColor); err != nil {
			panic(Error(err, "failed setting spinner color"))
		}

		s.Start()
		err := <-errc
		if err != nil {
			s.FinalMSG = failMsg
		}

		s.Stop()
	}()

	err := fn()
	errc <- err
	<-done
	return err
}
