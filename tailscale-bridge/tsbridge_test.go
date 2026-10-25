package tsbridge

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestParseInterfaces(t *testing.T) {
	desc := "wlan0 30 1500 true true false false true | 192.168.1.20/24 fe80::1/64\n" +
		"lo 1 65536 true false true false false | 127.0.0.1/8\n" +
		"garbage line without separator\n"
	got := parseInterfaces(desc)
	if len(got) != 2 {
		t.Fatalf("got %d interfaces, want 2", len(got))
	}
	w := got[0]
	if w.Name != "wlan0" || w.Index != 30 || w.MTU != 1500 || !w.IsUp() || w.IsLoopback() {
		t.Errorf("wlan0 parsed wrong: %+v flags=%v", w.Interface, w.Flags)
	}
	if len(w.AltAddrs) != 2 || w.AltAddrs[0].String() != "192.168.1.20/24" {
		t.Errorf("wlan0 addrs = %v", w.AltAddrs)
	}
	if !got[1].IsLoopback() {
		t.Errorf("lo should be loopback")
	}
}

func TestProxyRequiresToken(t *testing.T) {
	h := &proxyHandler{token: "secret"}
	for _, tc := range []struct {
		user, pass string
		want       int
	}{
		{"", "", http.StatusProxyAuthRequired},
		{"tsbridge", "wrong", http.StatusProxyAuthRequired},
	} {
		r := httptest.NewRequest(http.MethodGet, "http://immich:2283/api/server/ping", nil)
		if tc.pass != "" {
			r.SetBasicAuth(tc.user, tc.pass)
			r.Header.Set("Proxy-Authorization", r.Header.Get("Authorization"))
			r.Header.Del("Authorization")
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != tc.want {
			t.Errorf("pass=%q: got %d, want %d", tc.pass, w.Code, tc.want)
		}
	}
	r := httptest.NewRequest(http.MethodGet, "http://immich/", nil)
	r.SetBasicAuth("tsbridge", "secret")
	r.Header.Set("Proxy-Authorization", r.Header.Get("Authorization"))
	if !h.authorized(r) {
		t.Errorf("correct token rejected")
	}
	if r.Header.Get("Proxy-Authorization") != "" {
		t.Errorf("Proxy-Authorization should be stripped before forwarding")
	}
}
