package gateway

import (
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config describes one gateway instance. Every guest that connects gets its own
// copy of the virtual network described here; nothing is shared between sessions
// except the upstream servers the allow list points at.
type Config struct {
	Listen         string   `json:"listen"`          // HTTP address, e.g. ":8765"
	Path           string   `json:"path"`            // WebSocket path, e.g. "/eth"
	Tokens         []string `json:"tokens"`          // accepted ?token= values; empty = no auth (local dev only)
	OriginPatterns []string `json:"origin_patterns"` // browser origins allowed to open the socket; empty = same host only
	TrustProxy     bool     `json:"trust_proxy"`     // take the client IP from RealIPHeader (only from TrustedProxies)
	TrustedProxies []string `json:"trusted_proxies"` // CIDRs of the reverse proxies; empty = loopback only
	RealIPHeader   string   `json:"real_ip_header"`  // e.g. "CF-Connecting-IP"; default X-Forwarded-For (rightmost)
	RequireOrigin  bool     `json:"require_origin"`  // refuse handshakes without an Origin header (non-browser clients)

	// AllowPrivateUpstreams lists the private ranges (RFC 1918, CGNAT, ULA) an allow
	// rule may point at, e.g. the lobby server's address on a private network.
	// Link local / metadata (169.254.0.0/16) is never allowed.
	AllowPrivateUpstreams []string `json:"allow_private_upstreams"`

	trusted, privateOK []netip.Prefix

	Network NetworkConfig `json:"network"`
	DNS     DNSConfig     `json:"dns"`
	Allow   []AllowRule   `json:"allow"`
	Limits  Limits        `json:"limits"`

	PcapDir string `json:"pcap_dir"` // one pcap per session when set
}

type NetworkConfig struct {
	Gateway    string `json:"gateway"`     // router + DHCP + DNS address seen by the guest
	Client     string `json:"client"`      // address leased to the guest
	PrefixLen  int    `json:"prefix_len"`  // subnet size
	GatewayMAC string `json:"gateway_mac"` // MAC of the virtual router
	LeaseSecs  uint32 `json:"lease_secs"`
	DomainName string `json:"domain_name"`
}

type DNSConfig struct {
	// Overrides maps a host name or a "*.suffix" pattern to an IPv4 address.
	Overrides map[string]string `json:"overrides"`
	TTL       uint32            `json:"ttl"`
}

// AllowRule lets the guest reach Dst (an address the guest sees) and connects it
// to Upstream (a real host:port the gateway dials). Port "*" in Dst matches any
// port, and "*" in Upstream reuses the guest's destination port.
type AllowRule struct {
	Proto    string `json:"proto"` // "tcp" or "udp"
	Dst      string `json:"dst"`
	Upstream string `json:"upstream"`

	dstAddr netip.Addr
	dstPort int // 0 = any
	upHost  string
	upPort  int // 0 = same as guest destination
}

type Limits struct {
	MaxSessions      int     `json:"max_sessions"`
	MaxSessionsPerIP int     `json:"max_sessions_per_ip"`
	FramesPerSec     float64 `json:"frames_per_sec"`
	BytesPerSec      float64 `json:"bytes_per_sec"`
	MaxFlows         int     `json:"max_flows"`    // concurrent TCP+UDP flows per session
	IdleTimeout      string  `json:"idle_timeout"` // whole session, e.g. "10m"
	UDPIdle          string  `json:"udp_idle"`     // per UDP flow
	DialTimeout      string  `json:"dial_timeout"`

	// Per client address, over all its sessions.
	IPFramesPerSec   float64 `json:"ip_frames_per_sec"`
	IPBytesPerSec    float64 `json:"ip_bytes_per_sec"`
	ConnectsPerMinIP int     `json:"connects_per_min_per_ip"` // WebSocket handshakes per minute
	LogLinesPerSec   float64 `json:"log_lines_per_sec"`       // per session
	MaxLifetime      string  `json:"max_lifetime"`            // whole session, even when active ("" = 12h)

	idle, udpIdle, dial, lifetime time.Duration
}

// Default returns a configuration that only knows about a lobby server on the
// local machine. The DNS overrides send the Capcom and DNAS names to two
// addresses outside the guest subnet, so the guest routes them through us.
func Default() *Config {
	return &Config{
		Listen: "127.0.0.1:8765",
		Path:   "/eth",
		Network: NetworkConfig{
			Gateway:    "10.0.2.2",
			Client:     "10.0.2.15",
			PrefixLen:  24,
			GatewayMAC: "52:55:0a:00:02:02",
			LeaseSecs:  86400,
			DomainName: "reo.local",
		},
		DNS: DNSConfig{
			Overrides: map[string]string{
				"*.reo.capcom.sf.yav4.com": "198.18.0.10",
				"*.dnas.playstation.org":   "198.18.0.20",
			},
			TTL: 60,
		},
		Allow: []AllowRule{
			{Proto: "tcp", Dst: "198.18.0.10:8300", Upstream: "127.0.0.1:8300"},
			{Proto: "tcp", Dst: "198.18.0.10:8690", Upstream: "127.0.0.1:8690"},
			{Proto: "tcp", Dst: "198.18.0.10:80", Upstream: "127.0.0.1:8080"},
			{Proto: "tcp", Dst: "198.18.0.20:443", Upstream: "127.0.0.1:8443"},
		},
		Limits: Limits{
			MaxSessions:      64,
			MaxSessionsPerIP: 12, // players behind one NAT (family, LAN party, the 4 local test windows)
			FramesPerSec:     3000,
			BytesPerSec:      2 << 20,
			MaxFlows:         32,
			IdleTimeout:      "60m",
			UDPIdle:          "60s",
			DialTimeout:      "5s",
			IPFramesPerSec:   20000,
			IPBytesPerSec:    16 << 20,
			ConnectsPerMinIP: 120, // each page: a probe, the game socket and a retry every few seconds while the gateway is down
			LogLinesPerSec:   20,
			MaxLifetime:      "12h",
		},
	}
}

func Load(path string) (*Config, error) {
	c := Default()
	if path != "" {
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		if err := json.Unmarshal(data, c); err != nil {
			return nil, fmt.Errorf("%s: %w", path, err)
		}
	}
	return c, c.Validate()
}

func (c *Config) Validate() error {
	n := &c.Network
	for _, a := range []string{n.Gateway, n.Client} {
		if ip, err := netip.ParseAddr(a); err != nil || !ip.Is4() {
			return fmt.Errorf("network: %q is not an IPv4 address", a)
		}
	}
	if n.PrefixLen < 8 || n.PrefixLen > 30 {
		return fmt.Errorf("network: prefix_len %d out of range", n.PrefixLen)
	}
	if _, err := net.ParseMAC(n.GatewayMAC); err != nil {
		return fmt.Errorf("network: gateway_mac: %w", err)
	}
	for name, ip := range c.DNS.Overrides {
		if a, err := netip.ParseAddr(ip); err != nil || !a.Is4() {
			return fmt.Errorf("dns: override %s -> %q is not IPv4", name, ip)
		}
	}
	var err error
	if c.trusted, err = parsePrefixes(c.TrustedProxies, "trusted_proxies"); err != nil {
		return err
	}
	if len(c.trusted) == 0 {
		c.trusted = mustPrefixes("127.0.0.0/8", "::1/128")
	}
	if c.privateOK, err = parsePrefixes(c.AllowPrivateUpstreams, "allow_private_upstreams"); err != nil {
		return err
	}
	for _, p := range c.privateOK {
		if inAny(p.Addr(), neverUpstream) {
			return fmt.Errorf("allow_private_upstreams: %v is link local / metadata and is never allowed", p)
		}
	}
	for i := range c.Allow {
		if err := c.Allow[i].parse(); err != nil {
			return fmt.Errorf("allow[%d]: %w", i, err)
		}
		r := &c.Allow[i]
		if a, err := netip.ParseAddr(r.upHost); err == nil {
			if err := c.checkUpstream(a); err != nil {
				return fmt.Errorf("allow[%d]: %w", i, err)
			}
			// a wildcard port towards this machine would expose every local service (database, admin API...)
			if r.upPort == 0 && a.Unmap().IsLoopback() {
				return fmt.Errorf("allow[%d]: upstream port \"*\" towards loopback is not allowed; list each port", i)
			}
		}
		if inAny(r.dstAddr, neverUpstream) {
			return fmt.Errorf("allow[%d]: dst %v is link local / multicast", i, r.dstAddr)
		}
	}
	l := &c.Limits
	if l.lifetime, err = parseDuration(l.MaxLifetime, 12*time.Hour); err != nil {
		return err
	}
	if l.idle, err = parseDuration(l.IdleTimeout, 60*time.Minute); err != nil {
		return err
	}
	if l.udpIdle, err = parseDuration(l.UDPIdle, time.Minute); err != nil {
		return err
	}
	if l.dial, err = parseDuration(l.DialTimeout, 5*time.Second); err != nil {
		return err
	}
	return nil
}

func parsePrefixes(list []string, what string) ([]netip.Prefix, error) {
	var out []netip.Prefix
	for _, s := range list {
		if !strings.Contains(s, "/") {
			a, err := netip.ParseAddr(s)
			if err != nil {
				return nil, fmt.Errorf("%s: %q: %w", what, s, err)
			}
			out = append(out, netip.PrefixFrom(a, a.BitLen()))
			continue
		}
		p, err := netip.ParsePrefix(s)
		if err != nil {
			return nil, fmt.Errorf("%s: %q: %w", what, s, err)
		}
		out = append(out, p.Masked())
	}
	return out, nil
}

func parseDuration(s string, def time.Duration) (time.Duration, error) {
	if s == "" {
		return def, nil
	}
	return time.ParseDuration(s)
}

func (r *AllowRule) parse() error {
	if r.Proto != "tcp" && r.Proto != "udp" {
		return fmt.Errorf("proto %q", r.Proto)
	}
	host, port, err := net.SplitHostPort(r.Dst)
	if err != nil {
		return err
	}
	if r.dstAddr, err = netip.ParseAddr(host); err != nil || !r.dstAddr.Is4() {
		return fmt.Errorf("dst %q must be an IPv4 address", r.Dst)
	}
	if port != "*" {
		if r.dstPort, err = strconv.Atoi(port); err != nil || r.dstPort <= 0 || r.dstPort > 65535 {
			return fmt.Errorf("dst port %q", port)
		}
	}
	uh, up, err := net.SplitHostPort(r.Upstream)
	if err != nil {
		return err
	}
	r.upHost = uh
	if up != "*" {
		if r.upPort, err = strconv.Atoi(up); err != nil || r.upPort <= 0 || r.upPort > 65535 {
			return fmt.Errorf("upstream port %q", up)
		}
	}
	return nil
}

// Match returns the upstream address for a guest flow, or "" when the flow is
// not allowed. This is the only way traffic leaves the gateway.
func (c *Config) Match(proto string, dst netip.Addr, port uint16) string {
	for i := range c.Allow {
		r := &c.Allow[i]
		if r.Proto != proto || r.dstAddr != dst || (r.dstPort != 0 && r.dstPort != int(port)) {
			continue
		}
		p := r.upPort
		if p == 0 {
			p = int(port)
		}
		return net.JoinHostPort(r.upHost, strconv.Itoa(p))
	}
	return ""
}

// Resolve answers a guest DNS lookup from the override table only.
func (c *Config) Resolve(name string) (netip.Addr, bool) {
	name = strings.TrimSuffix(strings.ToLower(name), ".")
	if ip, ok := c.DNS.Overrides[name]; ok {
		return netip.MustParseAddr(ip), true
	}
	for pattern, ip := range c.DNS.Overrides {
		if suffix, ok := strings.CutPrefix(pattern, "*."); ok {
			if name == suffix || strings.HasSuffix(name, "."+suffix) {
				return netip.MustParseAddr(ip), true
			}
		}
	}
	return netip.Addr{}, false
}
