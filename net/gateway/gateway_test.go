package gateway

import (
	"bytes"
	"fmt"
	"encoding/binary"
	"context"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/google/gopacket"
	"github.com/google/gopacket/layers"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/link/ethernet"
	"gvisor.dev/gvisor/pkg/tcpip/network/arp"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

var guestMAC = net.HardwareAddr{0x00, 0x04, 0x1f, 0x12, 0x34, 0x56} // a Sony OUI, like a real adapter

type fixture struct {
	t        *testing.T
	cfg      *Config
	url      string
	upstream net.Listener
	got      chan []byte // what the upstream server received
}

// newFixture starts an echo server standing in for the lobby server and a
// gateway whose allow list points 198.18.0.10:8300 at it.
func newFixture(t *testing.T) *fixture {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	f := &fixture{t: t, upstream: ln, got: make(chan []byte, 8)}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				buf := make([]byte, 4096)
				for {
					n, err := c.Read(buf)
					if err != nil {
						return
					}
					f.got <- append([]byte(nil), buf[:n]...)
					c.Write(append([]byte("echo:"), buf[:n]...))
				}
			}()
		}
	}()
	cfg := Default()
	cfg.Allow = []AllowRule{{Proto: "tcp", Dst: "198.18.0.10:8300", Upstream: ln.Addr().String()}}
	cfg.PcapDir = os.Getenv("GATEWAY_PCAP_DIR")
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	f.cfg = cfg
	srv := httptest.NewServer(NewServer(cfg, log.New(testWriter{t}, "", 0)).Handler())
	f.url = "ws" + strings.TrimPrefix(srv.URL, "http") + cfg.Path
	t.Cleanup(func() { srv.Close(); ln.Close() })
	return f
}

type testWriter struct{ t *testing.T }

func (w testWriter) Write(p []byte) (int, error) {
	w.t.Log(strings.TrimSpace(string(p)))
	return len(p), nil
}

// peer is the test side of one session. A reader goroutine queues incoming
// frames, because cancelling a websocket Read closes the connection.
type peer struct {
	conn   *websocket.Conn
	frames chan []byte
}

func (f *fixture) dial() *peer {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, f.url, nil)
	if err != nil {
		f.t.Fatal(err)
	}
	p := &peer{conn: c, frames: make(chan []byte, 256)}
	go func() {
		for {
			_, data, err := c.Read(context.Background())
			if err != nil {
				close(p.frames)
				return
			}
			p.frames <- data
		}
	}()
	f.t.Cleanup(func() { c.CloseNow() })
	return p
}

func send(t *testing.T, c *peer, frame []byte) {
	t.Helper()
	if err := c.conn.Write(context.Background(), websocket.MessageBinary, frame); err != nil {
		t.Fatal(err)
	}
}

// expect reads frames until match accepts one or the timeout expires.
func expect(t *testing.T, c *peer, what string, match func(gopacket.Packet) bool) gopacket.Packet {
	t.Helper()
	timeout := time.After(3 * time.Second)
	for {
		select {
		case data, ok := <-c.frames:
			if !ok {
				t.Fatalf("waiting for %s: connection closed", what)
			}
			p := gopacket.NewPacket(data, layers.LayerTypeEthernet, gopacket.Default)
			if match(p) {
				return p
			}
		case <-timeout:
			t.Fatalf("timed out waiting for %s", what)
		}
	}
}

func expectNothing(t *testing.T, c *peer, what string, match func(gopacket.Packet) bool) {
	t.Helper()
	timeout := time.After(300 * time.Millisecond)
	for {
		select {
		case data := <-c.frames:
			if match(gopacket.NewPacket(data, layers.LayerTypeEthernet, gopacket.Default)) {
				t.Fatalf("unexpected %s", what)
			}
		case <-timeout:
			return
		}
	}
}

func serialize(t *testing.T, ls ...gopacket.SerializableLayer) []byte {
	t.Helper()
	buf := gopacket.NewSerializeBuffer()
	if err := gopacket.SerializeLayers(buf, gopacket.SerializeOptions{FixLengths: true, ComputeChecksums: true}, ls...); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func udpFrom(t *testing.T, src, dst net.IP, dstMAC net.HardwareAddr, sport, dport uint16, payload gopacket.SerializableLayer) []byte {
	eth := &layers.Ethernet{SrcMAC: guestMAC, DstMAC: dstMAC, EthernetType: layers.EthernetTypeIPv4}
	ip := &layers.IPv4{Version: 4, TTL: 64, Protocol: layers.IPProtocolUDP, SrcIP: src, DstIP: dst}
	u := &layers.UDP{SrcPort: layers.UDPPort(sport), DstPort: layers.UDPPort(dport)}
	u.SetNetworkLayerForChecksum(ip)
	return serialize(t, eth, ip, u, payload)
}

func dhcp(t *testing.T, kind layers.DHCPMsgType, xid uint32, extra ...layers.DHCPOption) []byte {
	opts := layers.DHCPOptions{layers.NewDHCPOption(layers.DHCPOptMessageType, []byte{byte(kind)})}
	opts = append(opts, extra...)
	opts = append(opts, layers.NewDHCPOption(layers.DHCPOptParamsRequest, []byte{1, 3, 6, 15}))
	d := &layers.DHCPv4{Operation: layers.DHCPOpRequest, HardwareType: layers.LinkTypeEthernet, HardwareLen: 6,
		Xid: xid, Flags: 0x8000, ClientIP: net.IPv4zero, YourClientIP: net.IPv4zero, NextServerIP: net.IPv4zero,
		RelayAgentIP: net.IPv4zero, ClientHWAddr: guestMAC, Options: opts}
	return udpFrom(t, net.IPv4zero, net.IPv4bcast, layers.EthernetBroadcast, 68, 67, d)
}

func dhcpReply(p gopacket.Packet, xid uint32) (*layers.DHCPv4, layers.DHCPMsgType) {
	l, ok := p.Layer(layers.LayerTypeDHCPv4).(*layers.DHCPv4)
	if !ok || l.Xid != xid {
		return nil, 0
	}
	for _, o := range l.Options {
		if o.Type == layers.DHCPOptMessageType {
			return l, layers.DHCPMsgType(o.Data[0])
		}
	}
	return l, 0
}

func option(d *layers.DHCPv4, t layers.DHCPOpt) []byte {
	for _, o := range d.Options {
		if o.Type == t {
			return o.Data
		}
	}
	return nil
}

func TestDHCP(t *testing.T) {
	f := newFixture(t)
	c := f.dial()
	send(t, c, dhcp(t, layers.DHCPMsgTypeDiscover, 0x1234))
	p := expect(t, c, "OFFER", func(p gopacket.Packet) bool { _, k := dhcpReply(p, 0x1234); return k == layers.DHCPMsgTypeOffer })
	offer, _ := dhcpReply(p, 0x1234)
	if !offer.YourClientIP.Equal(net.ParseIP("10.0.2.15")) {
		t.Fatalf("offered %v", offer.YourClientIP)
	}
	for _, check := range []struct {
		opt  layers.DHCPOpt
		want []byte
	}{
		{layers.DHCPOptSubnetMask, []byte{255, 255, 255, 0}},
		{layers.DHCPOptRouter, []byte{10, 0, 2, 2}},
		{layers.DHCPOptDNS, []byte{10, 0, 2, 2}},
		{layers.DHCPOptServerID, []byte{10, 0, 2, 2}},
	} {
		if got := option(offer, check.opt); !bytes.Equal(got, check.want) {
			t.Errorf("option %v = %v, want %v", check.opt, got, check.want)
		}
	}
	if eth := p.Layer(layers.LayerTypeEthernet).(*layers.Ethernet); !bytes.Equal(eth.DstMAC, guestMAC) {
		t.Errorf("offer sent to %v", eth.DstMAC)
	}
	send(t, c, dhcp(t, layers.DHCPMsgTypeRequest, 0x1235,
		layers.NewDHCPOption(layers.DHCPOptRequestIP, []byte{10, 0, 2, 15}),
		layers.NewDHCPOption(layers.DHCPOptServerID, []byte{10, 0, 2, 2})))
	expect(t, c, "ACK", func(p gopacket.Packet) bool { _, k := dhcpReply(p, 0x1235); return k == layers.DHCPMsgTypeAck })

	send(t, c, dhcp(t, layers.DHCPMsgTypeRequest, 0x1236, layers.NewDHCPOption(layers.DHCPOptRequestIP, []byte{10, 0, 2, 99})))
	expect(t, c, "NAK", func(p gopacket.Packet) bool { _, k := dhcpReply(p, 0x1236); return k == layers.DHCPMsgTypeNak })
}

func arpFrame(t *testing.T, sender, target net.IP) []byte {
	eth := &layers.Ethernet{SrcMAC: guestMAC, DstMAC: layers.EthernetBroadcast, EthernetType: layers.EthernetTypeARP}
	a := &layers.ARP{AddrType: layers.LinkTypeEthernet, Protocol: layers.EthernetTypeIPv4, HwAddressSize: 6, ProtAddressSize: 4,
		Operation: layers.ARPRequest, SourceHwAddress: guestMAC, SourceProtAddress: sender.To4(),
		DstHwAddress: make([]byte, 6), DstProtAddress: target.To4()}
	return serialize(t, eth, a)
}

func TestARP(t *testing.T) {
	f := newFixture(t)
	c := f.dial()
	isReply := func(p gopacket.Packet) bool {
		a, ok := p.Layer(layers.LayerTypeARP).(*layers.ARP)
		return ok && a.Operation == layers.ARPReply
	}
	// Address-conflict probe for the leased IP: nobody may answer.
	send(t, c, arpFrame(t, net.IPv4zero, net.ParseIP("10.0.2.15")))
	expectNothing(t, c, "ARP reply to a probe", isReply)
	send(t, c, arpFrame(t, net.ParseIP("10.0.2.15"), net.ParseIP("10.0.2.2")))
	p := expect(t, c, "ARP reply", isReply)
	a := p.Layer(layers.LayerTypeARP).(*layers.ARP)
	if net.HardwareAddr(a.SourceHwAddress).String() != f.cfg.Network.GatewayMAC || !net.IP(a.SourceProtAddress).Equal(net.ParseIP("10.0.2.2")) {
		t.Fatalf("reply %v %v", net.HardwareAddr(a.SourceHwAddress), net.IP(a.SourceProtAddress))
	}
}

func dnsQuery(t *testing.T, gwMAC net.HardwareAddr, id uint16, name string) []byte {
	q := &layers.DNS{ID: id, RD: true, Questions: []layers.DNSQuestion{{Name: []byte(name), Type: layers.DNSTypeA, Class: layers.DNSClassIN}}}
	return udpFrom(t, net.ParseIP("10.0.2.15"), net.ParseIP("10.0.2.2"), gwMAC, 40000+id, 53, q)
}

func TestDNS(t *testing.T) {
	f := newFixture(t)
	c := f.dial()
	gw, _ := net.ParseMAC(f.cfg.Network.GatewayMAC)
	answer := func(id uint16) func(gopacket.Packet) bool {
		return func(p gopacket.Packet) bool {
			d, ok := p.Layer(layers.LayerTypeDNS).(*layers.DNS)
			return ok && d.QR && d.ID == id
		}
	}
	cases := []struct {
		name string
		rc   layers.DNSResponseCode
		ip   string
	}{
		{"app01.reo.capcom.sf.yav4.com", layers.DNSResponseCodeNoErr, "198.18.0.10"},
		{"SNAP01.REO.CAPCOM.SF.YAV4.COM", layers.DNSResponseCodeNoErr, "198.18.0.10"},
		{"gate1.us.dnas.playstation.org", layers.DNSResponseCodeNoErr, "198.18.0.20"},
		{"www.google.com", layers.DNSResponseCodeNXDomain, ""},
		{"yav4.com.evil.example", layers.DNSResponseCodeNXDomain, ""},
	}
	for i, tc := range cases {
		id := uint16(100 + i)
		send(t, c, dnsQuery(t, gw, id, tc.name))
		d := expect(t, c, "DNS answer for "+tc.name, answer(id)).Layer(layers.LayerTypeDNS).(*layers.DNS)
		if d.ResponseCode != tc.rc {
			t.Errorf("%s: rcode %v, want %v", tc.name, d.ResponseCode, tc.rc)
			continue
		}
		if tc.ip != "" && (len(d.Answers) != 1 || !d.Answers[0].IP.Equal(net.ParseIP(tc.ip))) {
			t.Errorf("%s: answers %v, want %s", tc.name, d.Answers, tc.ip)
		}
	}
}

func synFrame(t *testing.T, gwMAC net.HardwareAddr, dst string, port uint16) []byte {
	eth := &layers.Ethernet{SrcMAC: guestMAC, DstMAC: gwMAC, EthernetType: layers.EthernetTypeIPv4}
	ip := &layers.IPv4{Version: 4, TTL: 64, Protocol: layers.IPProtocolTCP, SrcIP: net.ParseIP("10.0.2.15"), DstIP: net.ParseIP(dst)}
	tc := &layers.TCP{SrcPort: 1025, DstPort: layers.TCPPort(port), Seq: 1000, SYN: true, Window: 8192,
		Options: []layers.TCPOption{{OptionType: layers.TCPOptionKindMSS, OptionLength: 4, OptionData: []byte{0x05, 0xb4}}}}
	tc.SetNetworkLayerForChecksum(ip)
	return serialize(t, eth, ip, tc)
}

func TestTCPHandshakeAndDeny(t *testing.T) {
	f := newFixture(t)
	c := f.dial()
	gw, _ := net.ParseMAC(f.cfg.Network.GatewayMAC)
	// The gateway learns the guest MAC from this frame.
	send(t, c, arpFrame(t, net.ParseIP("10.0.2.15"), net.ParseIP("10.0.2.2")))
	tcpFrom := func(port layers.TCPPort, synack, rst bool) func(gopacket.Packet) bool {
		return func(p gopacket.Packet) bool {
			tc, ok := p.Layer(layers.LayerTypeTCP).(*layers.TCP)
			return ok && tc.SrcPort == port && tc.DstPort == 1025 && (!synack || (tc.SYN && tc.ACK)) && (!rst || tc.RST)
		}
	}
	send(t, c, synFrame(t, gw, "198.18.0.10", 8300))
	p := expect(t, c, "SYN-ACK from the allowed server", tcpFrom(8300, true, false))
	if tc := p.Layer(layers.LayerTypeTCP).(*layers.TCP); tc.Ack != 1001 {
		t.Fatalf("ack %d", tc.Ack)
	}
	send(t, c, synFrame(t, gw, "1.1.1.1", 80))
	expect(t, c, "RST for a destination outside the allow list", tcpFrom(80, false, true))
	send(t, c, synFrame(t, gw, "198.18.0.10", 22))
	expect(t, c, "RST for a port outside the allow list", tcpFrom(22, false, true))
}

// guestStack is a second userspace TCP/IP stack playing the PS2: it runs DHCP
// by hand, then opens a real TCP connection through the gateway.
func TestGuestStackEndToEnd(t *testing.T) {
	f := newFixture(t)
	c := f.dial()

	// DHCP first, exactly as the console would.
	send(t, c, dhcp(t, layers.DHCPMsgTypeDiscover, 7))
	expect(t, c, "OFFER", func(p gopacket.Packet) bool { _, k := dhcpReply(p, 7); return k == layers.DHCPMsgTypeOffer })
	send(t, c, dhcp(t, layers.DHCPMsgTypeRequest, 8, layers.NewDHCPOption(layers.DHCPOptRequestIP, []byte{10, 0, 2, 15})))
	expect(t, c, "ACK", func(p gopacket.Packet) bool { _, k := dhcpReply(p, 8); return k == layers.DHCPMsgTypeAck })

	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, arp.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	link := channel.New(256, 1514, tcpip.LinkAddress(guestMAC))
	if err := s.CreateNIC(1, ethernet.New(link)); err != nil {
		t.Fatal(err)
	}
	s.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddressWithPrefix{Address: tcpip.AddrFrom4([4]byte{10, 0, 2, 15}), PrefixLen: 24}}, stack.AddressProperties{})
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, Gateway: tcpip.AddrFrom4([4]byte{10, 0, 2, 2}), NIC: 1},
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		for {
			pkt := link.ReadContext(ctx)
			if pkt == nil {
				return
			}
			b := pkt.ToBuffer()
			frame := b.Flatten()
			b.Release()
			pkt.DecRef()
			c.conn.Write(ctx, websocket.MessageBinary, frame)
		}
	}()
	go func() {
		for data := range c.frames {
			pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(data)})
			link.InjectInbound(0, pkt)
			pkt.DecRef()
		}
	}()

	dialCtx, dialCancel := context.WithTimeout(ctx, 5*time.Second)
	defer dialCancel()
	conn, err := gonet.DialContextTCP(dialCtx, s, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 18, 0, 10}), Port: 8300}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatalf("dial lobby: %v", err)
	}
	// A lobby-style packet: 12-byte header (who, qsw, cmd 0x6101 LOGIN, len, pid, err, ff ff ff).
	hello := []byte{0x18, 0x01, 0x61, 0x01, 0x00, 0x04, 0x00, 0x01, 0x00, 0xff, 0xff, 0xff, 'T', 'E', 'S', 'T'}
	conn.Write(hello)
	select {
	case got := <-f.got:
		if !bytes.Equal(got, hello) {
			t.Fatalf("upstream got %x", got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("upstream never saw the packet")
	}
	reply := make([]byte, len(hello)+5)
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	if _, err := io.ReadFull(conn, reply); err != nil || !bytes.Equal(reply[5:], hello) {
		t.Fatalf("reply %x %v", reply, err)
	}
	conn.Close()

	_, err = gonet.DialContextTCP(dialCtx, s, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{93, 184, 216, 34}), Port: 80}, ipv4.ProtocolNumber)
	if err == nil {
		t.Fatal("connection outside the allow list succeeded")
	}
}

func TestLimits(t *testing.T) {
	f := newFixture(t)
	f.cfg.Limits.MaxSessionsPerIP = 1
	f.dial()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	c2, _, err := websocket.Dial(ctx, f.url, nil)
	if err != nil {
		t.Fatalf("second session: handshake failed: %v", err)
	}
	_, _, err = c2.Read(ctx)
	if websocket.CloseStatus(err) != CloseLimit {
		t.Fatalf("second session from the same IP was not refused with %d: %v", CloseLimit, err)
	}
	f.cfg.Tokens = []string{"s3cret"}
	if _, resp, err := websocket.Dial(ctx, f.url, nil); err == nil || resp == nil || resp.StatusCode != 403 {
		t.Fatalf("session without token was not refused: %v", err)
	}
}

func TestAllowMatching(t *testing.T) {
	cfg := Default()
	cfg.AllowPrivateUpstreams = []string{"10.1.0.0/16"}
	cfg.Allow = []AllowRule{
		{Proto: "tcp", Dst: "198.18.0.10:*", Upstream: "10.1.1.1:*"},
		{Proto: "udp", Dst: "198.18.0.11:3658", Upstream: "lobby.example:4000"},
	}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		proto, dst string
		port       uint16
		want       string
	}{
		{"tcp", "198.18.0.10", 8300, "10.1.1.1:8300"},
		{"udp", "198.18.0.10", 8300, ""},
		{"udp", "198.18.0.11", 3658, "lobby.example:4000"},
		{"udp", "198.18.0.11", 3659, ""},
		{"tcp", "8.8.8.8", 53, ""},
	}
	for _, tc := range cases {
		addr := netip.MustParseAddr(tc.dst)
		if got := cfg.Match(tc.proto, addr, tc.port); got != tc.want {
			t.Errorf("%s %s:%d -> %q, want %q", tc.proto, tc.dst, tc.port, got, tc.want)
		}
	}
}

func TestUpstreamPolicy(t *testing.T) {
	bad := []AllowRule{
		{Proto: "tcp", Dst: "198.18.0.10:80", Upstream: "169.254.169.254:80"}, // cloud metadata
		{Proto: "tcp", Dst: "198.18.0.10:80", Upstream: "192.168.1.1:80"},     // private, not listed
		{Proto: "tcp", Dst: "198.18.0.10:*", Upstream: "127.0.0.1:*"},         // every local port
		{Proto: "udp", Dst: "198.18.0.10:53", Upstream: "[fe80::1]:53"},
		{Proto: "tcp", Dst: "198.18.0.10:80", Upstream: "0.0.0.0:80"},
	}
	for _, r := range bad {
		cfg := Default()
		cfg.Allow = []AllowRule{r}
		if err := cfg.Validate(); err == nil {
			t.Errorf("rule %+v was accepted", r)
		}
	}
	cfg := Default()
	cfg.AllowPrivateUpstreams = []string{"169.254.0.0/16"}
	if cfg.Validate() == nil {
		t.Error("allow_private_upstreams accepted link local")
	}
	// a host name that resolves to a forbidden address is refused at dial time
	cfg = Default()
	cfg.Allow = []AllowRule{{Proto: "tcp", Dst: "198.18.0.10:80", Upstream: "localhost:80"}}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	for _, a := range []string{"169.254.169.254:80", "10.0.0.1:80", "[fe80::1]:80"} {
		if _, err := cfg.dialer().Dial("tcp", a); err == nil || !strings.Contains(err.Error(), "upstream ") {
			t.Errorf("dial %s: %v", a, err)
		}
	}
}

func TestRealIP(t *testing.T) {
	cfg := Default()
	cfg.TrustProxy = true
	cfg.TrustedProxies = []string{"10.9.0.0/16"}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	cases := []struct{ remote, hdr, want string }{
		{"203.0.113.5:1000", "1.2.3.4", "203.0.113.5"},              // not a trusted proxy: header ignored
		{"10.9.0.1:1000", "6.6.6.6, 198.51.100.7", "198.51.100.7"},  // spoofed leftmost entry ignored
		{"10.9.0.1:1000", "198.51.100.7, 10.9.0.2", "198.51.100.7"}, // chain of trusted proxies
		{"10.9.0.1:1000", "garbage", "10.9.0.1"},
		{"10.9.0.1:1000", "", "10.9.0.1"},
	}
	for _, c := range cases {
		if got := cfg.realIP(c.remote, c.hdr); got != c.want {
			t.Errorf("realIP(%s, %q) = %s, want %s", c.remote, c.hdr, got, c.want)
		}
	}
}

func TestConnectRateAndOrigin(t *testing.T) {
	f := newFixture(t)
	f.cfg.Limits.ConnectsPerMinIP = 3
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	codes := []int{}
	for i := 0; i < 5; i++ {
		c, resp, err := websocket.Dial(ctx, f.url, nil)
		if err == nil {
			c.Close(websocket.StatusNormalClosure, "")
			codes = append(codes, 101)
		} else if resp != nil {
			codes = append(codes, resp.StatusCode)
		}
		time.Sleep(50 * time.Millisecond)
	}
	if len(codes) == 0 || codes[len(codes)-1] != 429 {
		t.Fatalf("handshake rate limit not applied: %v", codes)
	}
	f.cfg.Limits.ConnectsPerMinIP = 0
	f.cfg.RequireOrigin = true
	if _, resp, err := websocket.Dial(ctx, f.url, nil); err == nil || resp == nil || resp.StatusCode != 403 {
		t.Fatalf("handshake without Origin accepted: %v", err)
	}
	h := http.Header{"Origin": {"https://evil.example"}}
	if _, resp, err := websocket.Dial(ctx, f.url, &websocket.DialOptions{HTTPHeader: h}); err == nil || resp == nil || resp.StatusCode != 403 {
		t.Fatalf("foreign Origin accepted: %v", err)
	}
}

func TestUpstreamControl(t *testing.T) {
	msg, ok := upstreamControl(append([]byte("REOGW-GONE"), 0x12, 0x34, 0xAB, 0xCD))
	if !ok || string(msg) != `{"type":"kk-peer-gone","tag":"1234abcd"}` {
		t.Fatalf("gone: %q %v", msg, ok)
	}
	msg, ok = upstreamControl(append([]byte("REOGW-LEFT"), 0x12, 0x34, 0xAB, 0xCD, 2))
	if !ok || string(msg) != `{"type":"kk-peer-left","tag":"1234abcd","slot":2}` {
		t.Fatalf("left: %q %v", msg, ok)
	}
	if msg, ok := upstreamControl([]byte("REOGW-LEFT")); !ok || msg != nil {
		t.Fatalf("own left notice must be swallowed: %q %v", msg, ok)
	}
	if msg, ok := upstreamControl([]byte("REOGW-OTHER")); !ok || msg != nil {
		t.Fatalf("unknown control must be swallowed: %q %v", msg, ok)
	}
	if msg, ok := upstreamControl([]byte("REOGW-GONE")); !ok || msg != nil {
		t.Fatalf("short gone must be swallowed: %q %v", msg, ok)
	}
	if _, ok := upstreamControl([]byte{0x00, 0x20, 0x00, 0x2D, 1, 2, 3, 4}); ok {
		t.Fatal("kk datagram must pass through")
	}
}

func TestRTTNotice(t *testing.T) {
	ln, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	out, err := net.Dial("udp", ln.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer out.Close()
	s := &session{udpOut: map[net.Conn]struct{}{out: {}}}
	s.notifyRTT(169)
	buf := make([]byte, 64)
	ln.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := ln.ReadFrom(buf)
	if err != nil || !bytes.Equal(buf[:n], append([]byte("REOGW-RTT"), 0, 0, 0, 169)) {
		t.Fatalf("got %x %v", buf[:n], err)
	}
	if s.lastRTT.Load() != 169 {
		t.Fatal("last rtt not kept")
	}
}

func TestGuestLeft(t *testing.T) {
	ln, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	out, err := net.Dial("udp", ln.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer out.Close()
	s := &session{udpOut: map[net.Conn]struct{}{out: {}}, log: log.New(io.Discard, "", 0), logs: logBudget{b: newBucket(100)}}
	for _, bad := range []string{`{"type":"left-scenario"}`, `{"type":"left-scenario","slot":9}`, `{"type":"x","slot":1}`, `nope`} {
		s.guestControl([]byte(bad))
	}
	s.guestControl([]byte(`{"type":"left-scenario","slot":2}`))
	buf := make([]byte, 64)
	ln.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := ln.ReadFrom(buf)
	if err != nil || !bytes.Equal(buf[:n], append([]byte("REOGW-LEFT"), 2)) {
		t.Fatalf("got %x %v", buf[:n], err)
	}
}

// TestRTTNoticeEndToEnd: a guest UDP flow to the kk lobby gets "REOGW-RTT" + ms on the upstream side after a
// WebSocket ping, and the round-trip notice is never seen by the guest.
func TestRTTNoticeEndToEnd(t *testing.T) {
	old := pingEvery
	pingEvery = 150 * time.Millisecond
	defer func() { pingEvery = old }()
	up, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer up.Close()
	f := newFixture(t)
	f.cfg.Allow = append(f.cfg.Allow, AllowRule{Proto: "udp", Dst: "198.18.0.10:9090", Upstream: up.LocalAddr().String()})
	if err := f.cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	c := f.dial()
	gw, _ := net.ParseMAC(f.cfg.Network.GatewayMAC)
	send(t, c, udpFrom(t, net.IPv4(10, 0, 2, 15), net.IPv4(198, 18, 0, 10), gw, 40000, 9090, gopacket.Payload("hello")))
	up.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 256)
	n, _, err := up.ReadFrom(buf)
	if err != nil || string(buf[:n]) != "hello" {
		t.Fatalf("first datagram %q %v", buf[:n], err)
	}
	n, _, err = up.ReadFrom(buf)
	if err != nil || n != 13 || !bytes.HasPrefix(buf[:n], []byte("REOGW-RTT")) {
		t.Fatalf("rtt notice %x %v", buf[:n], err)
	}
	t.Logf("rtt notice: %d ms", binary.BigEndian.Uint32(buf[9:13]))
}

// TestUDPRelayDelay measures what the gateway itself adds to a game datagram: guest -> WebSocket -> gateway ->
// UDP upstream (echo) -> gateway -> WebSocket -> guest, on loopback (logged; fails only above 20 ms median).
func TestUDPRelayDelay(t *testing.T) {
	up, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer up.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, a, err := up.ReadFrom(buf)
			if err != nil {
				return
			}
			if !bytes.HasPrefix(buf[:n], gatewayPrefix) {
				up.WriteTo(buf[:n], a)
			}
		}
	}()
	f := newFixture(t)
	f.cfg.Allow = append(f.cfg.Allow, AllowRule{Proto: "udp", Dst: "198.18.0.10:9090", Upstream: up.LocalAddr().String()})
	if err := f.cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	c := f.dial()
	gw, _ := net.ParseMAC(f.cfg.Network.GatewayMAC)
	var ds []time.Duration
	for i := 0; i < 300; i++ {
		p := []byte(fmt.Sprintf("game-%04d", i))
		sent := time.Now()
		send(t, c, udpFrom(t, net.IPv4(10, 0, 2, 15), net.IPv4(198, 18, 0, 10), gw, 40000, 9090, gopacket.Payload(p)))
		expect(t, c, "echo", func(pk gopacket.Packet) bool {
			a := pk.ApplicationLayer()
			return a != nil && bytes.Equal(a.Payload(), p)
		})
		ds = append(ds, time.Since(sent))
	}
	sort.Slice(ds, func(i, j int) bool { return ds[i] < ds[j] })
	t.Logf("gateway round trip on loopback: p50 %v p90 %v p99 %v max %v", ds[150], ds[270], ds[297], ds[299])
	if ds[150] > 20*time.Millisecond {
		t.Fatalf("p50 %v", ds[150])
	}
}
