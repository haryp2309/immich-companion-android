//go:build tools

// Keeps golang.org/x/mobile/bind in go.mod; gomobile bind needs it.
package tsbridge

import _ "golang.org/x/mobile/bind"
