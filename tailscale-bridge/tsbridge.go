// Package tsbridge embeds a userspace Tailscale node (tsnet) for the Android
// app and exposes it through a gomobile-friendly API.
//
// The node never creates a VPN interface. Instead, the bridge runs an HTTP
// proxy on 127.0.0.1 that dials through the tailnet. The proxy needs a random
// per-process token, so other apps on the device can't use it.
package tsbridge

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"os"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var (
	mu        sync.Mutex
	srv       *tsnet.Server
	proxyLn   net.Listener
	proxyTok  string
	lastError string
)

// Start brings up the tailnet node. It returns immediately; poll Status to
// follow progress and to get the interactive login URL. authKey may be empty.
func Start(stateDir, hostname, authKey string) error {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return nil
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return err
	}
	// Android apps have no HOME/TMPDIR; some Tailscale code paths expect them.
	os.Setenv("HOME", stateDir)
	os.Setenv("TMPDIR", stateDir)

	s := &tsnet.Server{
		Dir:      stateDir,
		Hostname: hostname,
		AuthKey:  authKey,
		UserLogf: log.Printf,
		Logf:     func(string, ...any) {},
	}
	if err := s.Start(); err != nil {
		lastError = err.Error()
		return err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		s.Close()
		return err
	}
	tok := make([]byte, 24)
	rand.Read(tok)

	srv, proxyLn, proxyTok, lastError = s, ln, hex.EncodeToString(tok), ""
	go http.Serve(ln, &proxyHandler{s: s, token: proxyTok})
	return nil
}

// Stop shuts the node down but keeps its state, so the next Start doesn't
// need to log in again.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	stopLocked()
}

func stopLocked() {
	if proxyLn != nil {
		proxyLn.Close()
		proxyLn = nil
	}
	if srv != nil {
		srv.Close()
		srv = nil
	}
}

// Logout logs the node out of the tailnet and stops it.
func Logout() error {
	mu.Lock()
	defer mu.Unlock()
	if srv == nil {
		return errors.New("not running")
	}
	lc, err := srv.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	err = lc.Logout(ctx)
	stopLocked()
	return err
}

// ProxyPort is the loopback port of the HTTP proxy, or 0 if the node isn't running.
func ProxyPort() int {
	mu.Lock()
	defer mu.Unlock()
	if proxyLn == nil {
		return 0
	}
	return proxyLn.Addr().(*net.TCPAddr).Port
}

// ProxyToken is the token that clients must send as proxy basic-auth
// password (any username).
func ProxyToken() string {
	mu.Lock()
	defer mu.Unlock()
	return proxyTok
}

type status struct {
	State     string   `json:"state"` // Stopped, NoState, NeedsLogin, NeedsMachineAuth, Starting, Running
	AuthURL   string   `json:"authUrl,omitempty"`
	IPs       []string `json:"ips,omitempty"`
	DNSName   string   `json:"dnsName,omitempty"`
	Tailnet   string   `json:"tailnet,omitempty"`
	LoginName string   `json:"loginName,omitempty"`
	Error     string   `json:"error,omitempty"`
}

// Status returns the node status as JSON.
func Status() string {
	mu.Lock()
	s, errStr := srv, lastError
	mu.Unlock()

	st := status{State: "Stopped", Error: errStr}
	if s != nil {
		fillStatus(s, &st)
	}
	b, _ := json.Marshal(st)
	return string(b)
}

func fillStatus(s *tsnet.Server, st *status) {
	lc, err := s.LocalClient()
	if err != nil {
		st.Error = err.Error()
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ts, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		st.Error = err.Error()
		return
	}
	st.State = ts.BackendState
	st.AuthURL = ts.AuthURL
	for _, ip := range ts.TailscaleIPs {
		st.IPs = append(st.IPs, ip.String())
	}
	if ts.Self != nil {
		st.DNSName = strings.TrimSuffix(ts.Self.DNSName, ".")
	}
	if ts.CurrentTailnet != nil {
		st.Tailnet = ts.CurrentTailnet.Name
	}
	if ts.Self != nil {
		if u, ok := ts.User[ts.Self.UserID]; ok {
			st.LoginName = u.LoginName
		}
	}
}

// SetInterfaces passes the device's network interfaces to Tailscale. Android
// blocks net.Interfaces (netlink) for apps, so the app reads them through
// java.net.NetworkInterface and sends them here. Each line is:
//
//	name index mtu up broadcast loopback pointToPoint multicast | addr/prefix addr/prefix ...
func SetInterfaces(desc string) {
	ifaces := parseInterfaces(desc)
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return ifaces, nil
	})
}

func parseInterfaces(desc string) []netmon.Interface {
	var out []netmon.Interface
	for _, line := range strings.Split(desc, "\n") {
		head, addrPart, ok := strings.Cut(line, "|")
		if !ok {
			continue
		}
		var name string
		var index, mtu int
		var up, broadcast, loopback, p2p, multicast bool
		if _, err := fmt.Sscan(head, &name, &index, &mtu, &up, &broadcast, &loopback, &p2p, &multicast); err != nil {
			continue
		}
		var flags net.Flags
		for f, set := range map[net.Flags]bool{
			net.FlagUp: up, net.FlagRunning: up, net.FlagBroadcast: broadcast,
			net.FlagLoopback: loopback, net.FlagPointToPoint: p2p, net.FlagMulticast: multicast,
		} {
			if set {
				flags |= f
			}
		}
		addrs := []net.Addr{}
		for _, a := range strings.Fields(addrPart) {
			if ip, ipnet, err := net.ParseCIDR(a); err == nil {
				ipnet.IP = ip
				addrs = append(addrs, ipnet)
			}
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{Index: index, MTU: mtu, Name: name, Flags: flags},
			AltAddrs:  addrs,
		})
	}
	return out
}

// proxyHandler is a minimal HTTP forward proxy (plain requests plus CONNECT)
// that sends all traffic over the tailnet.
type proxyHandler struct {
	s     *tsnet.Server
	token string
	rp    *httputil.ReverseProxy
	once  sync.Once
}

func (h *proxyHandler) authorized(r *http.Request) bool {
	auth := r.Header.Get("Proxy-Authorization")
	r.Header.Del("Proxy-Authorization")
	req := &http.Request{Header: http.Header{"Authorization": {auth}}}
	_, pass, ok := req.BasicAuth()
	return ok && pass == h.token
}

func (h *proxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !h.authorized(r) {
		w.Header().Set("Proxy-Authenticate", `Basic realm="tsbridge"`)
		http.Error(w, "proxy auth required", http.StatusProxyAuthRequired)
		return
	}
	if r.Method == http.MethodConnect {
		h.connect(w, r)
		return
	}
	if r.URL.Host == "" {
		http.Error(w, "absolute URL required", http.StatusBadRequest)
		return
	}
	h.once.Do(func() {
		h.rp = &httputil.ReverseProxy{
			Rewrite:       func(pr *httputil.ProxyRequest) { pr.Out.URL = pr.In.URL },
			FlushInterval: -1,
			Transport: &http.Transport{
				DialContext:           h.s.Dial,
				MaxIdleConnsPerHost:   4,
				IdleConnTimeout:       90 * time.Second,
				ResponseHeaderTimeout: 10 * time.Minute,
			},
		}
	})
	h.rp.ServeHTTP(w, r)
}

func (h *proxyHandler) connect(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	upstream, err := h.s.Dial(ctx, "tcp", r.Host)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		upstream.Close()
		http.Error(w, "hijack unsupported", http.StatusInternalServerError)
		return
	}
	client, buf, err := hj.Hijack()
	if err != nil {
		upstream.Close()
		return
	}
	client.Write([]byte("HTTP/1.1 200 Connection established\r\n\r\n"))
	go func() {
		// Bytes the client sent right after CONNECT may already be buffered.
		if n := buf.Reader.Buffered(); n > 0 {
			b, _ := buf.Reader.Peek(n)
			upstream.Write(b)
		}
		io.Copy(upstream, client)
		upstream.Close()
	}()
	io.Copy(client, upstream)
	client.Close()
}
