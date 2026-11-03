package gateway

import (
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Upstream policy: the allow list is the only way out of a session, and these
// checks keep a bad configuration (or a DNS name that later resolves somewhere
// else) from turning the gateway into a pivot towards the VPS's own network or
// the cloud metadata service. Loopback is allowed (the lobby server runs next to
// the gateway); private ranges only when listed in allow_private_upstreams.

var neverUpstream = mustPrefixes(
	"0.0.0.0/8",         // "this network"
	"169.254.0.0/16",    // link local, cloud metadata (169.254.169.254)
	"224.0.0.0/4",       // multicast
	"240.0.0.0/4",       // reserved + broadcast
	"fe80::/10",         // IPv6 link local
	"ff00::/8",          // IPv6 multicast
	"::/128",            // unspecified
	"fd00:ec2::254/128", // AWS IPv6 metadata (also inside fc00::/7)
)

var privateUpstream = mustPrefixes(
	"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", // RFC 1918
	"100.64.0.0/10", // carrier-grade NAT (some clouds use it internally)
	"fc00::/7",      // IPv6 unique local
)

func mustPrefixes(s ...string) []netip.Prefix {
	out := make([]netip.Prefix, len(s))
	for i, p := range s {
		out[i] = netip.MustParsePrefix(p)
	}
	return out
}

func inAny(a netip.Addr, ps []netip.Prefix) bool {
	for _, p := range ps {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// checkUpstream says whether the gateway may connect to a (resolved) address.
func (c *Config) checkUpstream(a netip.Addr) error {
	a = a.Unmap()
	if !a.IsValid() || inAny(a, neverUpstream) {
		return fmt.Errorf("upstream %v is never allowed (link local / metadata / multicast / unspecified)", a)
	}
	if inAny(a, privateUpstream) && !inAny(a, c.privateOK) {
		return fmt.Errorf("upstream %v is a private address; list it in allow_private_upstreams", a)
	}
	return nil
}

// dialer re-checks the address the kernel is about to connect to, so a host
// name in the allow list cannot be re-pointed (DNS rebinding) at a forbidden one.
func (c *Config) dialer() *net.Dialer {
	return &net.Dialer{
		Timeout: c.Limits.dial,
		Control: func(_, address string, _ syscall.RawConn) error {
			ap, err := netip.ParseAddrPort(address)
			if err != nil {
				return err
			}
			return c.checkUpstream(ap.Addr())
		},
	}
}

// ---- per client address ----------------------------------------------------

// ipState is shared by every session of one client address: the session count,
// the recent connection attempts and aggregate frame/byte buckets, so opening
// more sockets never buys more bandwidth than max_sessions_per_ip allows.
type ipState struct {
	sessions int
	connects []time.Time
	frames   *bucket
	bytes    *bucket
}

type ipTable struct {
	mu sync.Mutex
	m  map[string]*ipState
}

// logBudget keeps one session from flooding the log (every denied SYN or DNS
// query is a line): at most n lines per second, then a single "suppressed" note.
type logBudget struct {
	b          *bucket
	mu         sync.Mutex
	suppressed int
}

func (l *logBudget) allow() (ok bool, suppressed int) {
	if l.b.take(1) {
		l.mu.Lock()
		suppressed, l.suppressed = l.suppressed, 0
		l.mu.Unlock()
		return true, suppressed
	}
	l.mu.Lock()
	l.suppressed++
	l.mu.Unlock()
	return false, 0
}

// realIP picks the client address. Proxy headers are only believed when the TCP
// peer is a trusted proxy; X-Forwarded-For is read from the right (the entry the
// trusted proxy appended), never the client-controlled leftmost one.
func (c *Config) realIP(remote, header string) string {
	host, _, err := net.SplitHostPort(remote)
	if err != nil {
		host = remote
	}
	peer, err := netip.ParseAddr(host)
	if err != nil || !c.TrustProxy || !inAny(peer.Unmap(), c.trusted) || header == "" {
		return host
	}
	parts := strings.Split(header, ",")
	for i := len(parts) - 1; i >= 0; i-- {
		a, err := netip.ParseAddr(strings.TrimSpace(parts[i]))
		if err != nil {
			return host
		}
		if !inAny(a.Unmap(), c.trusted) || i == 0 {
			return a.Unmap().String()
		}
	}
	return host
}
