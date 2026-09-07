package config

import "encoding/json"

// Secret is a credential that cannot accidentally be printed.
//
// This is a mechanism, not a policy. "Remember not to log the API key" is a rule
// someone eventually breaks -- in an error message wrapping a request, in a
// %+v of a config struct while debugging, in a panic trace. A Secret renders as
// "[redacted]" through every path the standard library uses to turn a value into
// text, so the only way to get the real thing is to call Reveal, which appears
// once per adapter and is trivial to audit.
//
// The receivers are value receivers on purpose: a *Secret would still print the
// underlying string when the value, not the pointer, reached fmt.
type Secret string

const redacted = "[redacted]"

// String satisfies fmt.Stringer, covering %s, %v and print calls.
func (s Secret) String() string { return redacted }

// GoString covers %#v, which ignores Stringer.
func (s Secret) GoString() string { return redacted }

// MarshalJSON covers being serialised into a response or a structured log.
func (s Secret) MarshalJSON() ([]byte, error) { return json.Marshal(redacted) }

// MarshalYAML covers a config being written back out.
func (s Secret) MarshalYAML() (any, error) { return redacted, nil }

// Reveal returns the actual value. Every call site is a place a credential can
// escape, so there should be few and they should be obvious.
func (s Secret) Reveal() string { return string(s) }

func (s Secret) IsSet() bool { return string(s) != "" }
