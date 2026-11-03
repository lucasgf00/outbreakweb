package gateway

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/link/ethernet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	nicID        = 1
	maxFrame     = 1514
	minFrame     = 14
	etherARP     = 0x0806
	etherIPv4    = 0x0800
	outQueueSize = 1024
	pingTimeout  = 45 * time.Second
)

var pingEvery = 15 * time.Second // a variable for the tests

// closeNotice is written on an upstream UDP flow when it ends (see forwardUDP).
var closeNotice = []byte("REOGW-BYE")

// gatewayPrefix marks datagrams between the upstream and the gateway itself: never forwarded to the guest.
var gatewayPrefix = []byte("REOGW-")

// peerGone is sent by the kk lobby on a member's flow when another member of its started room left for good:
// "REOGW-GONE" + u32 big-endian session tag. The guest gets a WebSocket text message instead (see upstreamControl).
var peerGone = []byte("REOGW-GONE")

// rttNotice is written on every open upstream UDP flow after each liveness ping, and once when a flow opens if a
// round trip is already known: "REOGW-RTT" + u32 big-endian ms (browser <-> gateway WebSocket round trip). The kk
// lobby keeps it per player and logs it per game (one-way delay between two members ~ (rtt_a + rtt_b) / 2, since all
// game data goes through its relay). Not a kk datagram (no trailer): other services ignore it.
var rttNotice = []byte("REOGW-RTT")

func rttDatagram(ms int64) []byte {
	if ms < 0 {
		ms = 0
	}
	b := make([]byte, len(rttNotice)+4)
	copy(b, rttNotice)
	binary.BigEndian.PutUint32(b[len(rttNotice):], uint32(min(ms, 1<<31)))
	return b
}

// leftNotice goes both ways. From the gateway on every open upstream UDP flow when the runtime says its own player
// left the scenario while the session goes on (dead or zombified, on the results screen): "REOGW-LEFT" + u8 slot
// (its slot in the game's peer table). From the kk lobby to the members still playing: "REOGW-LEFT" + u32 tag + u8
// slot, which becomes {"type":"kk-peer-left","tag":"xxxxxxxx","slot":n} for their runtime (it drops that slot at
// once, even though the player still sends: protocol.md 6.17).
var leftNotice = []byte("REOGW-LEFT")

// guestControl handles a WebSocket text message from the guest: {"type":"left-scenario","slot":n} from the runtime,
// {"type":"trk",...} from the web page (trkNotice). Anything else is ignored.
func (s *session) guestControl(m []byte) {
	if len(m) > 256 {
		return
	}
	var c struct {
		Type  string `json:"type"`
		Slot  *int   `json:"slot"`
		Ch    *int   `json:"ch"`
		Virus *int   `json:"virus"`
		St    int    `json:"st"`
		Av    int    `json:"av"`
		Herb  int    `json:"herb"`
		Bleed int    `json:"bleed"`
	}
	if json.Unmarshal(m, &c) != nil || c.Slot == nil || *c.Slot < 0 || *c.Slot > 7 {
		return
	}
	switch c.Type {
	case "left-scenario":
		s.logf("runtime: own player left the scenario (slot %d)", *c.Slot)
		s.notifyLeft(byte(*c.Slot))
	case "trk":
		if *c.Slot > 3 || c.Ch == nil || *c.Ch < 0 || *c.Ch > 7 || c.Virus == nil || *c.Virus < 0 || *c.Virus > 1000 ||
			c.St < 0 || c.St > 255 || c.Av < 0 || c.Av > 0xFFFF || c.Herb < 0 || c.Herb > 0xFFFF || c.Bleed < 0 || c.Bleed > 0xFFFF {
			return
		}
		if now := time.Now(); now.Sub(s.lastTrk) >= trkGap {
			s.lastTrk = now
			s.sendUpstream(trkDatagram(byte(*c.Slot), byte(*c.Ch), byte(c.St), uint16(*c.Virus), uint16(c.Av), uint16(c.Herb), uint16(c.Bleed)))
		}
	}
}

// trkNotice carries what the status tracker of the web page shows and the game does not send to the other
// players (the virus; also the status byte and the antivirus, herb and bleeding timers; protocol.md 6.18). From
// the gateway on every open upstream UDP flow: "REOGW-TRK" + u8 slot, u8 character, u8 status, u16 virus in
// tenths of a percent (0-1000), u16 antivirus, u16 herb, u16 bleeding (big endian; the game's raw timers). The kk
// lobby relays it to the other members of the same game as "REOGW-TRK" + u32 tag + the same 11 bytes, which
// becomes {"type":"kk-peer-trk",...} for their page (upstreamControl). At most one every trkGap per WebSocket.
var trkNotice = []byte("REOGW-TRK")

const trkBody = 11

var trkGap = 400 * time.Millisecond // a variable for the tests

func trkDatagram(slot, ch, st byte, virus, av, herb, bleed uint16) []byte {
	b := make([]byte, len(trkNotice)+trkBody)
	n := copy(b, trkNotice)
	b[n], b[n+1], b[n+2] = slot, ch, st
	binary.BigEndian.PutUint16(b[n+3:], virus)
	binary.BigEndian.PutUint16(b[n+5:], av)
	binary.BigEndian.PutUint16(b[n+7:], herb)
	binary.BigEndian.PutUint16(b[n+9:], bleed)
	return b
}

// sendUpstream writes d on every open upstream UDP flow of this session.
func (s *session) sendUpstream(d []byte) {
	s.udpMu.Lock()
	defer s.udpMu.Unlock()
	for c := range s.udpOut {
		c.Write(d)
	}
}

// notifyLeft tells every open upstream UDP flow that this player left the scenario (see leftNotice).
func (s *session) notifyLeft(slot byte) {
	s.sendUpstream(append(append([]byte{}, leftNotice...), slot))
}

// upstreamControl turns a gateway datagram from the upstream into the text message for the runtime (nil = none).
// handled is false for ordinary game traffic, which is forwarded as is.
func upstreamControl(p []byte) (msg []byte, handled bool) {
	if !bytes.HasPrefix(p, gatewayPrefix) {
		return nil, false
	}
	if len(p) == len(peerGone)+4 && bytes.HasPrefix(p, peerGone) {
		tag := binary.BigEndian.Uint32(p[len(peerGone):])
		return []byte(fmt.Sprintf(`{"type":"kk-peer-gone","tag":"%08x"}`, tag)), true
	}
	if len(p) == len(leftNotice)+5 && bytes.HasPrefix(p, leftNotice) {
		tag := binary.BigEndian.Uint32(p[len(leftNotice):])
		return []byte(fmt.Sprintf(`{"type":"kk-peer-left","tag":"%08x","slot":%d}`, tag, p[len(leftNotice)+4])), true
	}
	if len(p) == len(trkNotice)+4+trkBody && bytes.HasPrefix(p, trkNotice) {
		tag, q := binary.BigEndian.Uint32(p[len(trkNotice):]), p[len(trkNotice)+4:]
		be := binary.BigEndian.Uint16
		if q[0] > 3 || q[1] > 7 || be(q[3:]) > 1000 {
			return nil, true
		}
		return []byte(fmt.Sprintf(`{"type":"kk-peer-trk","tag":"%08x","slot":%d,"ch":%d,"st":%d,"virus":%d,"av":%d,"herb":%d,"bleed":%d}`,
			tag, q[0], q[1], q[2], be(q[3:]), be(q[5:]), be(q[7:]), be(q[9:]))), true
	}
	return nil, true
}

// session is one guest's private network: a router, a DHCP server, a DNS server
// that only knows the override table, and a userspace TCP/IP stack (gVisor
// netstack) that terminates the guest's connections and re-creates them towards
// allow-listed upstreams.
type session struct {
	cfg  *Config
	id   uint64
	peer string
	log  *log.Logger

	stack *stack.Stack
	link  *channel.Endpoint

	gwMAC    net.HardwareAddr
	gwIP     netip.Addr
	clientIP netip.Addr
	mask     net.IPMask

	mu        sync.Mutex
	clientMAC net.HardwareAddr
	neighbor  bool

	out      chan []byte
	ctl      chan []byte // WebSocket text messages for the runtime (old runtimes ignore text messages)
	ctx      context.Context
	cancel   context.CancelFunc
	flows    atomic.Int32
	udpMu    sync.Mutex
	udpOut   map[net.Conn]struct{} // open upstream UDP flows, for the round-trip notice
	lastTrk  time.Time             // last trkNotice sent (guestControl: read loop only)
	lastRTT  atomic.Int64          // last ping round trip in ms (-1 = none yet)
	flowWG   sync.WaitGroup
	activity atomic.Int64
	frames   *bucket
	bytes    *bucket
	ipFrames *bucket // shared by every session of the same client address (nil in tests)
	ipBytes  *bucket
	logs     logBudget
	pcap     *pcapWriter

	stats struct {
		in, out, dropped, denied atomic.Uint64
		// round trip of the liveness pings (browser -> Cloudflare -> here and back), ms: for the network
		// measurements of the desync work (reoutbreak docs/notes/backlog.md, 23/10)
		rttN, rttSum, rttMax atomic.Int64
		rttMin               atomic.Int64
	}
	country string // CF-IPCountry of the WebSocket request ("" without Cloudflare)
}

func newSession(cfg *Config, id uint64, peer string, logger *log.Logger) (*session, error) {
	gwMAC, _ := net.ParseMAC(cfg.Network.GatewayMAC)
	s := &session{
		cfg:      cfg,
		id:       id,
		peer:     peer,
		log:      logger,
		gwMAC:    gwMAC,
		gwIP:     netip.MustParseAddr(cfg.Network.Gateway),
		clientIP: netip.MustParseAddr(cfg.Network.Client),
		mask:     net.CIDRMask(cfg.Network.PrefixLen, 32),
		out:      make(chan []byte, outQueueSize),
		ctl:      make(chan []byte, 16),
		udpOut:   map[net.Conn]struct{}{},
		frames:   newBucket(cfg.Limits.FramesPerSec),
		bytes:    newBucket(cfg.Limits.BytesPerSec),
		logs:     logBudget{b: newBucket(cfg.Limits.LogLinesPerSec)},
	}
	s.lastRTT.Store(-1)
	if cfg.PcapDir != "" {
		p, err := newPcap(cfg.PcapDir, id)
		if err != nil {
			return nil, err
		}
		s.pcap = p
	}

	s.stack = stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol4},
	})
	s.link = channel.New(512, maxFrame, tcpip.LinkAddress(gwMAC))
	if err := s.stack.CreateNIC(nicID, ethernet.New(s.link)); err != nil {
		return nil, fmt.Errorf("create nic: %v", err)
	}
	if err := s.stack.AddProtocolAddress(nicID, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddressWithPrefix{Address: tcpip.AddrFrom4(s.gwIP.As4()), PrefixLen: cfg.Network.PrefixLen},
	}, stack.AddressProperties{}); err != nil {
		return nil, fmt.Errorf("add address: %v", err)
	}
	// Promiscuous: accept packets for any destination (the allow-listed upstreams
	// live outside the guest subnet). Spoofing: answer from those addresses.
	// ARP never reaches the stack, so spoofing cannot make it claim the guest's IP.
	s.stack.SetPromiscuousMode(nicID, true)
	s.stack.SetSpoofing(nicID, true)
	s.stack.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: nicID}})

	tcpFwd := tcp.NewForwarder(s.stack, 0, 16, s.forwardTCP)
	s.stack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)
	udpFwd := udp.NewForwarder(s.stack, s.forwardUDP)
	s.stack.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)
	return s, nil
}

func (s *session) logf(format string, args ...any) {
	ok, suppressed := s.logs.allow()
	if !ok {
		return
	}
	if suppressed > 0 {
		s.log.Printf("session %d [%s] (%d log lines suppressed)", s.id, s.peer, suppressed)
	}
	s.log.Printf("session %d [%s] %s", s.id, s.peer, fmt.Sprintf(format, args...))
}

func (s *session) touch() { s.activity.Store(time.Now().UnixNano()) }

func (s *session) run(parent context.Context, conn *websocket.Conn) {
	s.ctx, s.cancel = context.WithCancel(parent)
	defer s.cancel()
	s.touch()
	s.logf("connected")

	var wg sync.WaitGroup
	wg.Add(4)
	go func() { // liveness: a browser that vanished without closing (sleep, lost Wi-Fi) leaves a half-open TCP
		// connection that would only end at idle_timeout; a WebSocket ping it cannot answer ends the session
		// (and its UDP flows send the close notice). Browsers and the native runtime answer pings themselves.
		defer wg.Done()
		t := time.NewTicker(pingEvery)
		defer t.Stop()
		for {
			select {
			case <-s.ctx.Done():
				return
			case <-t.C:
				ctx, cancel := context.WithTimeout(s.ctx, pingTimeout)
				sent := time.Now()
				err := conn.Ping(ctx)
				cancel()
				if err == nil {
					ms := time.Since(sent).Milliseconds()
					s.noteRTT(ms)
					s.notifyRTT(ms)
				}
				if err != nil && s.ctx.Err() == nil {
					s.logf("ping lost: %v", err)
					s.cancel()
					return
				}
			}
		}
	}()
	go func() { // stack -> guest
		defer wg.Done()
		for {
			pkt := s.link.ReadContext(s.ctx)
			if pkt == nil {
				return
			}
			buf := pkt.ToBuffer()
			frame := buf.Flatten()
			buf.Release()
			pkt.DecRef()
			s.send(frame)
		}
	}()
	go func() { // queue -> websocket
		defer wg.Done()
		defer s.cancel()
		for {
			select {
			case <-s.ctx.Done():
				return
			case f := <-s.out:
				ctx, cancel := context.WithTimeout(s.ctx, 10*time.Second)
				err := conn.Write(ctx, websocket.MessageBinary, f)
				cancel()
				if err != nil {
					return
				}
			case m := <-s.ctl:
				ctx, cancel := context.WithTimeout(s.ctx, 10*time.Second)
				err := conn.Write(ctx, websocket.MessageText, m)
				cancel()
				if err != nil {
					return
				}
			}
		}
	}()
	go func() { // idle watchdog
		defer wg.Done()
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-s.ctx.Done():
				return
			case <-t.C:
				if time.Since(time.Unix(0, s.activity.Load())) > s.cfg.Limits.idle {
					s.logf("idle timeout")
					s.cancel()
					return
				}
			}
		}
	}()

	for {
		typ, frame, err := conn.Read(s.ctx)
		if err != nil {
			break
		}
		if typ != websocket.MessageBinary {
			// not routed, but still counted: a text-message flood costs the same as a frame flood
			if s.frames.take(1) {
				s.guestControl(frame)
			}
			continue
		}
		s.touch()
		s.receive(frame)
	}
	s.cancel()
	conn.Close(websocket.StatusNormalClosure, "")
	s.flowWG.Wait()
	s.link.Close()
	s.stack.Close()
	wg.Wait()
	s.stack.Wait()
	if s.pcap != nil {
		s.pcap.Close()
	}
	rtt := "rtt -"
	if n := s.stats.rttN.Load(); n > 0 {
		rtt = fmt.Sprintf("rtt ms min %d avg %d max %d (%d pings)", s.stats.rttMin.Load(), s.stats.rttSum.Load()/n, s.stats.rttMax.Load(), n)
	}
	cc := s.country
	if cc == "" {
		cc = "-"
	}
	s.logf("closed: %d frames in, %d out, %d dropped, %d flows denied, %s, country %s",
		s.stats.in.Load(), s.stats.out.Load(), s.stats.dropped.Load(), s.stats.denied.Load(), rtt, cc)
}

// noteRTT records one ping round trip.
func (s *session) noteRTT(ms int64) {
	if s.stats.rttN.Add(1) == 1 {
		s.stats.rttMin.Store(ms)
	} else {
		for v := s.stats.rttMin.Load(); ms < v && !s.stats.rttMin.CompareAndSwap(v, ms); v = s.stats.rttMin.Load() {
		}
	}
	s.stats.rttSum.Add(ms)
	for v := s.stats.rttMax.Load(); ms > v && !s.stats.rttMax.CompareAndSwap(v, ms); v = s.stats.rttMax.Load() {
	}
}

// notifyRTT tells every open upstream UDP flow the round trip just measured (see rttNotice).
func (s *session) notifyRTT(ms int64) {
	s.lastRTT.Store(ms)
	d := rttDatagram(ms)
	s.udpMu.Lock()
	defer s.udpMu.Unlock()
	for c := range s.udpOut {
		c.Write(d)
	}
}

// send queues a frame for the guest.
func (s *session) send(frame []byte) {
	if s.pcap != nil {
		s.pcap.Write(frame)
	}
	select {
	case s.out <- frame:
		s.stats.out.Add(1)
	default:
		s.stats.dropped.Add(1)
	}
}

func (s *session) drop(reason string, frame []byte) {
	if s.stats.dropped.Add(1) <= 20 {
		s.logf("drop (%s), %d bytes", reason, len(frame))
	}
}

// receive handles one frame from the guest.
func (s *session) receive(frame []byte) {
	defer func() { // a parser bug on a hostile frame must cost one frame, not the process
		if e := recover(); e != nil {
			s.drop(fmt.Sprintf("panic %v", e), frame)
		}
	}()
	if len(frame) < minFrame || len(frame) > maxFrame+4 {
		s.drop("bad length", frame)
		return
	}
	n := float64(len(frame))
	if !s.frames.take(1) || !s.bytes.take(n) ||
		(s.ipFrames != nil && !s.ipFrames.take(1)) || (s.ipBytes != nil && !s.ipBytes.take(n)) {
		s.drop("rate limit", frame)
		return
	}
	s.stats.in.Add(1)
	if s.pcap != nil {
		s.pcap.Write(frame)
	}
	src := net.HardwareAddr(frame[6:12])
	s.mu.Lock()
	if s.clientMAC == nil {
		if src[0]&1 != 0 {
			s.mu.Unlock()
			s.drop("multicast source", frame)
			return
		}
		s.clientMAC = append(net.HardwareAddr(nil), src...)
		s.logf("guest MAC %s", s.clientMAC)
	} else if string(s.clientMAC) != string(src) {
		s.mu.Unlock()
		s.drop("second MAC", frame)
		return
	}
	s.mu.Unlock()

	switch binary.BigEndian.Uint16(frame[12:14]) {
	case etherARP:
		s.handleARP(frame)
	case etherIPv4:
		s.handleIPv4(frame)
	default:
		// IPv6 and anything else is not routed.
	}
}

func (s *session) learnClient() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.neighbor || s.clientMAC == nil {
		return
	}
	s.stack.AddStaticNeighbor(nicID, ipv4.ProtocolNumber, tcpip.AddrFrom4(s.clientIP.As4()), tcpip.LinkAddress(s.clientMAC))
	s.neighbor = true
}

func (s *session) handleARP(frame []byte) {
	a := frame[14:]
	if len(a) < 28 || binary.BigEndian.Uint16(a[0:2]) != 1 || binary.BigEndian.Uint16(a[2:4]) != etherIPv4 || a[4] != 6 || a[5] != 4 {
		return
	}
	op := binary.BigEndian.Uint16(a[6:8])
	spa, _ := netip.AddrFromSlice(a[14:18])
	tpa, _ := netip.AddrFromSlice(a[24:28])
	if spa == s.clientIP {
		s.learnClient()
	}
	if op != 1 {
		return
	}
	// Proxy ARP for everything except the guest's own address, so probes and
	// gratuitous announcements of the leased IP never see a conflict.
	if tpa == s.clientIP || tpa == spa || tpa.IsUnspecified() {
		return
	}
	reply := make([]byte, 42)
	copy(reply[0:6], a[8:14])
	copy(reply[6:12], s.gwMAC)
	binary.BigEndian.PutUint16(reply[12:14], etherARP)
	r := reply[14:]
	copy(r[0:6], a[0:6])
	binary.BigEndian.PutUint16(r[6:8], 2)
	copy(r[8:14], s.gwMAC)
	copy(r[14:18], a[24:28])
	copy(r[18:24], a[8:14])
	copy(r[24:28], a[14:18])
	s.send(reply)
}

func (s *session) handleIPv4(frame []byte) {
	ip := header.IPv4(frame[14:])
	if !ip.IsValid(len(frame) - 14) {
		s.drop("bad IPv4", frame)
		return
	}
	src := netip.AddrFrom4(ip.SourceAddress().As4())
	dst := netip.AddrFrom4(ip.DestinationAddress().As4())
	if ip.Protocol() == uint8(header.UDPProtocolNumber) && ip.FragmentOffset() == 0 && ip.Flags()&header.IPv4FlagMoreFragments == 0 {
		u := header.UDP(ip.Payload())
		if len(u) >= header.UDPMinimumSize {
			switch {
			case u.DestinationPort() == 67:
				s.handleDHCP(frame)
				return
			case u.DestinationPort() == 53 && dst == s.gwIP && src == s.clientIP:
				s.handleDNS(frame)
				return
			}
		}
	}
	if src != s.clientIP {
		s.drop("source "+src.String(), frame)
		return
	}
	s.learnClient()
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), frame...))})
	s.link.InjectInbound(header.IPv4ProtocolNumber, pkt)
	pkt.DecRef()
}

func (s *session) acquireFlow() bool {
	if n := s.flows.Add(1); s.cfg.Limits.MaxFlows > 0 && int(n) > s.cfg.Limits.MaxFlows {
		s.flows.Add(-1)
		return false
	}
	return true
}

func (s *session) forwardTCP(r *tcp.ForwarderRequest) {
	id := r.ID()
	dst := netip.AddrFrom4(id.LocalAddress.As4())
	upstream := s.cfg.Match("tcp", dst, id.LocalPort)
	if upstream == "" {
		s.stats.denied.Add(1)
		s.logf("TCP %s:%d denied (not in allow list)", dst, id.LocalPort)
		r.Complete(true)
		return
	}
	if !s.acquireFlow() {
		s.logf("TCP %s:%d denied (too many flows)", dst, id.LocalPort)
		r.Complete(true)
		return
	}
	out, err := s.cfg.dialer().Dial("tcp", upstream)
	if err != nil {
		s.flows.Add(-1)
		s.logf("TCP %s:%d -> %s failed: %v", dst, id.LocalPort, upstream, err)
		r.Complete(true)
		return
	}
	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		s.flows.Add(-1)
		out.Close()
		r.Complete(true)
		return
	}
	r.Complete(false)
	in := gonet.NewTCPConn(&wq, ep)
	s.logf("TCP %d -> %s:%d connected to %s", id.RemotePort, dst, id.LocalPort, upstream)
	s.flowWG.Add(1)
	go func() {
		defer s.flowWG.Done()
		defer s.flows.Add(-1)
		up, down := pipe(s.ctx, in, out, s.touch)
		s.logf("TCP %d -> %s:%d closed (%d bytes up, %d down)", id.RemotePort, dst, id.LocalPort, up, down)
	}()
}

func (s *session) forwardUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	dst := netip.AddrFrom4(id.LocalAddress.As4())
	upstream := s.cfg.Match("udp", dst, id.LocalPort)
	if upstream == "" {
		s.stats.denied.Add(1)
		s.logf("UDP %s:%d denied (not in allow list)", dst, id.LocalPort)
		return true
	}
	if !s.acquireFlow() {
		return true
	}
	out, err := s.cfg.dialer().Dial("udp", upstream)
	if err != nil {
		s.flows.Add(-1)
		s.logf("UDP %s:%d -> %s failed: %v", dst, id.LocalPort, upstream, err)
		return true
	}
	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		s.flows.Add(-1)
		out.Close()
		return true
	}
	in := gonet.NewUDPConn(&wq, ep)
	s.logf("UDP %d -> %s:%d via %s", id.RemotePort, dst, id.LocalPort, upstream)
	s.flowWG.Add(1)
	go func() {
		defer s.flowWG.Done()
		defer s.flows.Add(-1)
		defer in.Close()
		defer out.Close()
		s.udpMu.Lock()
		s.udpOut[out] = struct{}{}
		if ms := s.lastRTT.Load(); ms >= 0 {
			out.Write(rttDatagram(ms))
		}
		s.udpMu.Unlock()
		defer func() { s.udpMu.Lock(); delete(s.udpOut, out); s.udpMu.Unlock() }()
		idle := s.cfg.Limits.udpIdle
		done := make(chan struct{}, 2)
		// the flow stays open while either direction has traffic: a game client that only listens
		// (e.g. the US kk lobby while waiting in a room) still gets the server's pushes
		var last atomic.Int64
		last.Store(time.Now().UnixNano())
		relay := func(dst net.Conn, src net.Conn, fromUpstream bool) {
			buf := make([]byte, 2048)
			for {
				src.SetReadDeadline(time.Now().Add(idle))
				n, err := src.Read(buf)
				if err != nil {
					if ne, ok := err.(net.Error); ok && ne.Timeout() && time.Since(time.Unix(0, last.Load())) < idle {
						continue
					}
					break
				}
				last.Store(time.Now().UnixNano())
				s.touch()
				if fromUpstream {
					if msg, ok := upstreamControl(buf[:n]); ok {
						if msg != nil {
							s.logf("%s", msg)
							select {
							case s.ctl <- msg:
							default:
							}
						}
						continue
					}
				}
				dst.Write(buf[:n])
			}
			done <- struct{}{}
		}
		go relay(out, in, false)
		go relay(in, out, true)
		select {
		case <-done:
		case <-s.ctx.Done():
		}
		// tell the upstream that this flow is gone (WebSocket closed, ping lost, idle): the kk lobby drops the
		// player at once instead of waiting out its silence timeout, which has to be long because a slow scenario
		// load can keep the game quiet for minutes. Not a kk datagram (no trailer): other services ignore it.
		out.Write(closeNotice)
	}()
	return true
}

// pipe copies both ways until either side closes; it returns bytes guest->upstream
// and upstream->guest.
func pipe(ctx context.Context, guest, upstream net.Conn, touch func()) (int64, int64) {
	var up, down int64
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		up, _ = io.Copy(upstream, activity{guest, touch})
		if c, ok := upstream.(*net.TCPConn); ok {
			c.CloseWrite()
		} else {
			upstream.Close()
		}
	}()
	go func() {
		defer wg.Done()
		down, _ = io.Copy(guest, activity{upstream, touch})
		if c, ok := guest.(*gonet.TCPConn); ok {
			c.CloseWrite()
		} else {
			guest.Close()
		}
	}()
	stop := context.AfterFunc(ctx, func() { guest.Close(); upstream.Close() })
	wg.Wait()
	stop()
	guest.Close()
	upstream.Close()
	return up, down
}

type activity struct {
	r     io.Reader
	touch func()
}

func (a activity) Read(p []byte) (int, error) {
	n, err := a.r.Read(p)
	if n > 0 {
		a.touch()
	}
	return n, err
}
