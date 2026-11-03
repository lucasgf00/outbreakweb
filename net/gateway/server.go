package gateway

import (
	"context"
	"crypto/subtle"
	"fmt"
	"log"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

// Server accepts WebSocket connections that carry raw Ethernet frames, one frame
// per binary message, and gives each one a private virtual network.
type Server struct {
	cfg    *Config
	logger *log.Logger

	mu     sync.Mutex
	ips    ipTable
	total  int
	nextID atomic.Uint64
}

func NewServer(cfg *Config, logger *log.Logger) *Server {
	if logger == nil {
		logger = log.Default()
	}
	return &Server{cfg: cfg, logger: logger, ips: ipTable{m: map[string]*ipState{}}}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc(s.cfg.Path, s.serveEth)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		s.mu.Lock()
		n := s.total
		s.mu.Unlock()
		fmt.Fprintf(w, "ok %d sessions\n", n)
	})
	return mux
}

func (s *Server) clientIP(r *http.Request) string {
	h := s.cfg.RealIPHeader
	if h == "" {
		h = "X-Forwarded-For"
	}
	return s.cfg.realIP(r.RemoteAddr, r.Header.Get(h))
}

func (s *Server) authorized(r *http.Request) bool {
	if len(s.cfg.Tokens) == 0 {
		return true
	}
	got := r.URL.Query().Get("token")
	for _, t := range s.cfg.Tokens {
		if subtle.ConstantTimeCompare([]byte(got), []byte(t)) == 1 {
			return true
		}
	}
	return false
}

// Session refusals told to the client with close code CloseLimit (4429) and one of these reasons.
const (
	CloseLimit       websocket.StatusCode = 4429
	ReasonIPLimit                         = "connection limit: too many sessions from this address"
	ReasonServerFull                      = "connection limit: the gateway is full"
)

// admit reserves a session slot for ip, or says why not.
func (s *Server) admit(ip string) (*ipState, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	l := s.cfg.Limits
	now := time.Now()
	st := s.ips.m[ip]
	if st == nil {
		st = &ipState{frames: newBucket(l.IPFramesPerSec), bytes: newBucket(l.IPBytesPerSec)}
		s.ips.m[ip] = st
	}
	// sliding one-minute window of handshakes (counted even when refused, so a
	// client hammering the endpoint stays out)
	keep := st.connects[:0]
	for _, t := range st.connects {
		if now.Sub(t) < time.Minute {
			keep = append(keep, t)
		}
	}
	st.connects = append(keep, now)
	if l.ConnectsPerMinIP > 0 && len(st.connects) > l.ConnectsPerMinIP {
		if len(st.connects) > 4*l.ConnectsPerMinIP {
			st.connects = st.connects[len(st.connects)-4*l.ConnectsPerMinIP:]
		}
		return nil, "too many connections"
	}
	if l.MaxSessions > 0 && s.total >= l.MaxSessions {
		return nil, ReasonServerFull
	}
	if l.MaxSessionsPerIP > 0 && st.sessions >= l.MaxSessionsPerIP {
		return nil, ReasonIPLimit
	}
	s.total++
	st.sessions++
	return st, ""
}

func (s *Server) release(ip string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.total--
	if st := s.ips.m[ip]; st != nil {
		st.sessions--
	}
}

// sweep forgets addresses with no sessions and no recent handshakes.
func (s *Server) sweep() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	for ip, st := range s.ips.m {
		if st.sessions <= 0 && (len(st.connects) == 0 || now.Sub(st.connects[len(st.connects)-1]) > time.Minute) {
			delete(s.ips.m, ip)
		}
	}
}

func (s *Server) serveEth(w http.ResponseWriter, r *http.Request) {
	ip := s.clientIP(r)
	if !s.authorized(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	if s.cfg.RequireOrigin && r.Header.Get("Origin") == "" {
		http.Error(w, "origin required", http.StatusForbidden)
		return
	}
	st, why := s.admit(ip)
	if st == nil {
		if why == ReasonServerFull || why == ReasonIPLimit {
			// A browser cannot read the HTTP status of a refused WebSocket handshake, so a session limit is
			// told on an accepted socket: close code 4429 with the reason (the page shows "connection limit").
			// Handshake floods (connects_per_min_per_ip) still get a plain 429.
			s.logger.Printf("refused %s: %s", ip, why)
			if conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{OriginPatterns: s.cfg.OriginPatterns}); err == nil {
				conn.Close(CloseLimit, why)
			}
			return
		}
		http.Error(w, why, http.StatusTooManyRequests)
		return
	}
	defer s.release(ip)
	// OriginPatterns empty = the Origin host must equal the request Host (library default)
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{OriginPatterns: s.cfg.OriginPatterns})
	if err != nil {
		return
	}
	conn.SetReadLimit(maxFrame + 4)
	id := s.nextID.Add(1)
	sess, err := newSession(s.cfg, id, ip, s.logger)
	if err != nil {
		s.logger.Printf("session %d: %v", id, err)
		conn.Close(websocket.StatusInternalError, "setup failed")
		return
	}
	sess.ipFrames, sess.ipBytes = st.frames, st.bytes
	if cc := r.Header.Get("CF-IPCountry"); len(cc) == 2 {
		sess.country = cc
	}
	ctx, cancel := context.WithTimeout(r.Context(), s.cfg.Limits.lifetime)
	defer cancel()
	sess.run(ctx, conn)
}

// ListenAndServe runs the gateway until ctx ends.
func (s *Server) ListenAndServe(ctx context.Context) error {
	srv := &http.Server{
		Addr:              s.cfg.Listen,
		Handler:           s.Handler(),
		ReadHeaderTimeout: 10 * time.Second, // slow handshakes (slowloris) do not hold sockets
		MaxHeaderBytes:    16 << 10,
	}
	go func() {
		<-ctx.Done()
		srv.Close()
	}()
	go func() {
		t := time.NewTicker(time.Minute)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				s.sweep()
			}
		}
	}()
	s.logger.Printf("gateway listening on ws://%s%s", s.cfg.Listen, s.cfg.Path)
	err := srv.ListenAndServe()
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}
