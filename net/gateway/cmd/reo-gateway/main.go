// Command reo-gateway bridges PS2 network adapters emulated in the browser (or
// the native runner) to an allow-listed set of servers. Each WebSocket carries
// raw Ethernet frames, one per binary message.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"log"
	"os"
	"os/signal"

	gateway "github.com/reoutbreak/gateway"
)

func main() {
	configPath := flag.String("config", "", "JSON configuration file (defaults are used when empty)")
	listen := flag.String("listen", "", "override the listen address")
	pcapDir := flag.String("pcap", "", "write one pcap per session into this directory")
	dump := flag.Bool("print-config", false, "print the effective configuration and exit")
	flag.Parse()

	cfg, err := gateway.Load(*configPath)
	if err != nil {
		log.Fatal(err)
	}
	if *listen != "" {
		cfg.Listen = *listen
	}
	if *pcapDir != "" {
		cfg.PcapDir = *pcapDir
	}
	if *dump {
		e := json.NewEncoder(os.Stdout)
		e.SetIndent("", "  ")
		e.Encode(cfg)
		return
	}
	if len(cfg.Tokens) == 0 {
		log.Printf("warning: no tokens configured, anyone who can reach %s can open a session", cfg.Listen)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	if err := gateway.NewServer(cfg, log.Default()).ListenAndServe(ctx); err != nil {
		log.Fatal(err)
	}
}
