// Package auth Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// vespa login command
// Author: ldalves
package auth

import (
	"bufio"
	"io"
	"os"

	"github.com/mattn/go-isatty"
)

var (
	Input    = os.Stdin
	Output   = os.Stdout
	Messages = os.Stderr
)

func IsInputTerminal() bool {
	return isatty.IsTerminal(Input.Fd())
}

func IsOutputTerminal() bool {
	return isatty.IsTerminal(Output.Fd())
}

func PipedInput() []byte {
	if !IsInputTerminal() {
		reader := bufio.NewReader(Input)
		var pipedInput []byte

		for {
			input, err := reader.ReadBytes('\n')
			if err == io.EOF {
				break
			} else if err != nil {
				panic(Error(err, "unable to read from pipe"))
			}
			pipedInput = append(pipedInput, input...)
		}

		return pipedInput
	}

	return []byte{}
}
