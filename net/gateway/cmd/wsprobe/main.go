// wsprobe opens N WebSocket sessions to a gateway at once (like N browser windows on one PC) and reports, for
// each, whether it stays open or is refused (close code and reason). Local testing only.
//
//	go run ./cmd/wsprobe -url ws://127.0.0.1:8765/eth -n 6 -origin http://localhost:8620
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"time"

	"github.com/coder/websocket"
)

func main() {
	url := flag.String("url", "ws://127.0.0.1:8765/eth", "gateway URL")
	n := flag.Int("n", 4, "sessions")
	origin := flag.String("origin", "http://localhost:8620", "Origin header")
	flag.Parse()
	var conns []*websocket.Conn
	for i := 0; i < *n; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		c, resp, err := websocket.Dial(ctx, *url, &websocket.DialOptions{HTTPHeader: http.Header{"Origin": {*origin}}})
		cancel()
		if err != nil {
			code := 0
			if resp != nil {
				code = resp.StatusCode
			}
			fmt.Printf("session %d: handshake refused (HTTP %d): %v\n", i+1, code, err)
			continue
		}
		// a refused session is closed by the gateway right away; an accepted one stays silent until the guest
		// talks. (A Read with a deadline closes the socket when it expires, so read in the background.)
		got := make(chan error, 1)
		go func() { _, _, err := c.Read(context.Background()); got <- err }()
		select {
		case err = <-got:
			var ce websocket.CloseError
			if errors.As(err, &ce) {
				fmt.Printf("session %d: refused, close %d %q\n", i+1, ce.Code, ce.Reason)
			} else {
				fmt.Printf("session %d: error %v\n", i+1, err)
			}
			continue
		case <-time.After(700 * time.Millisecond):
		}
		fmt.Printf("session %d: open\n", i+1)
		conns = append(conns, c)
	}
	for _, c := range conns {
		c.Close(websocket.StatusNormalClosure, "")
	}
}
