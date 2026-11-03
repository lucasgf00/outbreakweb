package gateway

import (
	"bytes"
	"io"
	"log"
	"net"
	"testing"
	"time"
)

// TestGuestTrk: the page's {"type":"trk",...} becomes one "REOGW-TRK" datagram upstream; bad fields and a second
// message inside trkGap send nothing.
func TestGuestTrk(t *testing.T) {
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
	for _, bad := range []string{
		`{"type":"trk","slot":1,"ch":2}`,                                                                 // no virus
		`{"type":"trk","slot":4,"ch":2,"virus":10}`,                                                      // slot out of range
		`{"type":"trk","slot":1,"ch":8,"virus":10}`,                                                      // character
		`{"type":"trk","slot":1,"ch":2,"virus":1001}`,                                                    // virus over 100 %
		`{"type":"trk","slot":1,"ch":2,"virus":10,"av":70000}`,                                           // timer
		`{"type":"trk","slot":1,"ch":2,"virus":-1}`,                                                      // negative
		`{"type":"trk","slot":1,"ch":2,"virus":10,"st":256}`,                                             // status byte
		`{"type":"trk","slot":"1","ch":2,"virus":10}`,                                                    // not a number
		`{"type":"trk","slot":1,"ch":2,"virus":10,"x":"` + string(bytes.Repeat([]byte("a"), 300)) + `"}`, // too long
	} {
		s.guestControl([]byte(bad))
	}
	s.guestControl([]byte(`{"type":"trk","slot":1,"ch":7,"st":2,"virus":343,"av":0,"herb":600,"bleed":1115}`))
	s.guestControl([]byte(`{"type":"trk","slot":1,"ch":7,"st":2,"virus":344}`)) // inside trkGap: dropped
	buf := make([]byte, 64)
	ln.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := ln.ReadFrom(buf)
	want := append([]byte("REOGW-TRK"), 1, 7, 2, 0x01, 0x57, 0, 0, 0x02, 0x58, 0x04, 0x5B)
	if err != nil || !bytes.Equal(buf[:n], want) {
		t.Fatalf("got %x %v, want %x", buf[:n], err, want)
	}
	ln.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
	if n, _, err := ln.ReadFrom(buf); err == nil {
		t.Fatalf("second message inside trkGap sent: %x", buf[:n])
	}
	s.lastTrk = time.Now().Add(-trkGap)
	s.guestControl([]byte(`{"type":"trk","slot":1,"ch":7,"virus":345}`))
	ln.SetReadDeadline(time.Now().Add(2 * time.Second))
	if n, _, err = ln.ReadFrom(buf); err != nil || n != len(want) || buf[12] != 0x01 || buf[13] != 0x59 {
		t.Fatalf("after the gap: %x %v", buf[:n], err)
	}
}

func TestUpstreamTrk(t *testing.T) {
	d := append([]byte("REOGW-TRK"), 0x12, 0x34, 0xAB, 0xCD, 1, 7, 2, 0x01, 0x57, 0, 0, 0x02, 0x58, 0x04, 0x5B)
	msg, ok := upstreamControl(d)
	if !ok || string(msg) != `{"type":"kk-peer-trk","tag":"1234abcd","slot":1,"ch":7,"st":2,"virus":343,"av":0,"herb":600,"bleed":1115}` {
		t.Fatalf("trk: %q %v", msg, ok)
	}
	bad := append([]byte{}, d...)
	bad[13] = 4 // slot
	if msg, ok := upstreamControl(bad); !ok || msg != nil {
		t.Fatalf("bad slot must be swallowed: %q %v", msg, ok)
	}
	if msg, ok := upstreamControl(append([]byte("REOGW-TRK"), 1, 7, 2, 0, 1, 0, 0, 0, 0, 0, 0)); !ok || msg != nil {
		t.Fatalf("own trk notice (no tag) must be swallowed: %q %v", msg, ok)
	}
}
