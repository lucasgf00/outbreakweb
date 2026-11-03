package gateway

import (
	"encoding/binary"
	"net"
	"net/netip"
	"strings"

	"github.com/google/gopacket"
	"github.com/google/gopacket/layers"
)

// udpFrame builds an Ethernet/IPv4/UDP frame from the virtual router.
func (s *session) udpFrame(dstMAC net.HardwareAddr, src, dst netip.Addr, sport, dport uint16, payload []byte) []byte {
	eth := &layers.Ethernet{SrcMAC: s.gwMAC, DstMAC: dstMAC, EthernetType: layers.EthernetTypeIPv4}
	ip := &layers.IPv4{Version: 4, TTL: 64, Protocol: layers.IPProtocolUDP, SrcIP: src.AsSlice(), DstIP: dst.AsSlice()}
	udp := &layers.UDP{SrcPort: layers.UDPPort(sport), DstPort: layers.UDPPort(dport)}
	udp.SetNetworkLayerForChecksum(ip)
	buf := gopacket.NewSerializeBuffer()
	if err := gopacket.SerializeLayers(buf, gopacket.SerializeOptions{FixLengths: true, ComputeChecksums: true},
		eth, ip, udp, gopacket.Payload(payload)); err != nil {
		return nil
	}
	return buf.Bytes()
}

// ---- DHCP ----------------------------------------------------------------

const (
	dhcpDiscover = 1
	dhcpOffer    = 2
	dhcpRequest  = 3
	dhcpDecline  = 4
	dhcpAck      = 5
	dhcpNak      = 6
	dhcpRelease  = 7
	dhcpInform   = 8
)

var dhcpNames = map[byte]string{1: "DISCOVER", 2: "OFFER", 3: "REQUEST", 4: "DECLINE", 5: "ACK", 6: "NAK", 7: "RELEASE", 8: "INFORM"}

func (s *session) handleDHCP(frame []byte) {
	p := gopacket.NewPacket(frame, layers.LayerTypeEthernet, gopacket.Default)
	l := p.Layer(layers.LayerTypeDHCPv4)
	if l == nil {
		s.drop("bad DHCP", frame)
		return
	}
	req := l.(*layers.DHCPv4)
	if req.Operation != layers.DHCPOpRequest || len(req.ClientHWAddr) != 6 {
		return
	}
	var kind byte
	var requested netip.Addr
	var hostname string
	for _, o := range req.Options {
		switch o.Type {
		case layers.DHCPOptMessageType:
			if len(o.Data) == 1 {
				kind = o.Data[0]
			}
		case layers.DHCPOptRequestIP:
			if len(o.Data) == 4 {
				requested = netip.AddrFrom4([4]byte(o.Data))
			}
		case layers.DHCPOptHostname:
			hostname = string(o.Data)
		}
	}
	ciaddr, _ := netip.AddrFromSlice(req.ClientIP.To4())
	s.logf("DHCP %s xid %08x from %s (requested %v, ciaddr %v, host %q)", dhcpNames[kind], req.Xid, req.ClientHWAddr, requested, ciaddr, hostname)

	var reply byte
	switch kind {
	case dhcpDiscover:
		reply = dhcpOffer
	case dhcpRequest:
		want := requested
		if !want.IsValid() {
			want = ciaddr
		}
		if want.IsValid() && !want.IsUnspecified() && want != s.clientIP {
			reply = dhcpNak
		} else {
			reply = dhcpAck
		}
	case dhcpInform:
		reply = dhcpAck
	case dhcpDecline, dhcpRelease:
		return
	default:
		return
	}

	yiaddr := s.clientIP
	if kind == dhcpInform || reply == dhcpNak {
		yiaddr = netip.IPv4Unspecified()
	}
	msg := make([]byte, 240, 548)
	msg[0] = 2 // BOOTREPLY
	msg[1] = 1 // Ethernet
	msg[2] = 6
	binary.BigEndian.PutUint32(msg[4:8], req.Xid)
	binary.BigEndian.PutUint16(msg[10:12], req.Flags)
	if kind == dhcpInform {
		copy(msg[12:16], req.ClientIP.To4())
	}
	y := yiaddr.As4()
	copy(msg[16:20], y[:])
	gw := s.gwIP.As4()
	copy(msg[20:24], gw[:])
	copy(msg[28:34], req.ClientHWAddr)
	binary.BigEndian.PutUint32(msg[236:240], 0x63825363)
	opt := func(t byte, data ...byte) { msg = append(append(msg, t, byte(len(data))), data...) }
	u32 := func(v uint32) []byte { return binary.BigEndian.AppendUint32(nil, v) }
	opt(53, reply)
	opt(54, gw[:]...)
	if reply != dhcpNak {
		if kind != dhcpInform {
			opt(51, u32(s.cfg.Network.LeaseSecs)...)
			opt(58, u32(s.cfg.Network.LeaseSecs/2)...)
			opt(59, u32(s.cfg.Network.LeaseSecs/8*7)...)
		}
		opt(1, s.mask...)
		opt(3, gw[:]...)
		opt(6, gw[:]...)
		if d := s.cfg.Network.DomainName; d != "" {
			opt(15, []byte(d)...)
		}
	}
	msg = append(msg, 255)
	for len(msg) < 300 { // BOOTP minimum, some old clients insist
		msg = append(msg, 0)
	}
	// Broadcast unless the client already owns an address it can receive on.
	dst := netip.AddrFrom4([4]byte{255, 255, 255, 255})
	if kind == dhcpInform && ciaddr.IsValid() && !ciaddr.IsUnspecified() {
		dst = ciaddr
	}
	if f := s.udpFrame(req.ClientHWAddr, s.gwIP, dst, 67, 68, msg); f != nil {
		s.send(f)
		s.logf("DHCP %s xid %08x -> %v/%d router %v dns %v", dhcpNames[reply], req.Xid, yiaddr, s.cfg.Network.PrefixLen, s.gwIP, s.gwIP)
	}
	if reply == dhcpAck {
		s.learnClient()
	}
}

// ---- DNS -----------------------------------------------------------------

func (s *session) handleDNS(frame []byte) {
	p := gopacket.NewPacket(frame, layers.LayerTypeEthernet, gopacket.Default)
	l := p.Layer(layers.LayerTypeDNS)
	udpLayer := p.Layer(layers.LayerTypeUDP)
	if l == nil || udpLayer == nil {
		s.drop("bad DNS", frame)
		return
	}
	q := l.(*layers.DNS)
	u := udpLayer.(*layers.UDP)
	if q.QR || len(q.Questions) != 1 {
		return
	}
	question := q.Questions[0]
	name := string(question.Name)
	resp := &layers.DNS{
		ID: q.ID, QR: true, OpCode: q.OpCode, AA: true, RD: q.RD, RA: true,
		Questions: q.Questions,
	}
	ip, known := s.cfg.Resolve(name)
	switch {
	case !known:
		resp.ResponseCode = layers.DNSResponseCodeNXDomain
		s.logf("DNS %s %s -> NXDOMAIN", question.Type, name)
	case question.Type == layers.DNSTypeA:
		resp.Answers = []layers.DNSResourceRecord{{
			Name: question.Name, Type: layers.DNSTypeA, Class: layers.DNSClassIN,
			TTL: s.cfg.DNS.TTL, IP: ip.AsSlice(),
		}}
		s.logf("DNS A %s -> %s", name, ip)
	default:
		s.logf("DNS %s %s -> no data", question.Type, strings.ToLower(name))
	}
	buf := gopacket.NewSerializeBuffer()
	if err := resp.SerializeTo(buf, gopacket.SerializeOptions{FixLengths: true}); err != nil {
		return
	}
	s.mu.Lock()
	mac := s.clientMAC
	s.mu.Unlock()
	if f := s.udpFrame(mac, s.gwIP, s.clientIP, 53, uint16(u.SrcPort), buf.Bytes()); f != nil {
		s.send(f)
	}
}
