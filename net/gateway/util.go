package gateway

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/google/gopacket"
	"github.com/google/gopacket/layers"
	"github.com/google/gopacket/pcapgo"
)

// bucket is a token bucket refilled at rate tokens per second, with one second
// of burst. A rate of 0 disables the limit.
type bucket struct {
	mu     sync.Mutex
	rate   float64
	tokens float64
	last   time.Time
}

func newBucket(rate float64) *bucket { return &bucket{rate: rate, tokens: rate, last: time.Now()} }

func (b *bucket) take(n float64) bool {
	if b.rate <= 0 {
		return true
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	now := time.Now()
	b.tokens = min(b.rate, b.tokens+now.Sub(b.last).Seconds()*b.rate)
	b.last = now
	if b.tokens < n {
		return false
	}
	b.tokens -= n
	return true
}

type pcapWriter struct {
	mu sync.Mutex
	f  *os.File
	w  *pcapgo.Writer
}

func newPcap(dir string, id uint64) (*pcapWriter, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	name := filepath.Join(dir, fmt.Sprintf("session-%s-%d.pcap", time.Now().Format("20060102-150405.000"), id))
	f, err := os.Create(name)
	if err != nil {
		return nil, err
	}
	w := pcapgo.NewWriter(f)
	if err := w.WriteFileHeader(65536, layers.LinkTypeEthernet); err != nil {
		f.Close()
		return nil, err
	}
	return &pcapWriter{f: f, w: w}, nil
}

func (p *pcapWriter) Write(frame []byte) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.w.WritePacket(gopacket.CaptureInfo{Timestamp: time.Now(), CaptureLength: len(frame), Length: len(frame)}, frame)
}

func (p *pcapWriter) Close() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.f.Close()
}
