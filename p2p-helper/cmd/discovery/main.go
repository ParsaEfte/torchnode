// Discovery-only adapter for the pinned geth discv5 implementation.
package main

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/log"
	"github.com/ethereum/go-ethereum/p2p/discover"
	"github.com/ethereum/go-ethereum/p2p/enode"
	"github.com/ethereum/go-ethereum/p2p/netutil"
	"github.com/ethereum/go-ethereum/rlp"
)

type config struct {
	PrivateKey      string   `json:"privateKey"`
	Bootstraps      []string `json:"bootstraps"`
	DurationSeconds int      `json:"durationSeconds"`
}
type event struct {
	Type          string `json:"type"`
	At            string `json:"at"`
	NodeID        string `json:"nodeId,omitempty"`
	RLP           string `json:"rlp,omitempty"`
	Endpoint      string `json:"endpoint,omitempty"`
	Provenance    string `json:"provenance,omitempty"`
	Authenticated bool   `json:"authenticated,omitempty"`
	Code          string `json:"code,omitempty"`
	Detail        string `json:"detail,omitempty"`
}
type sink struct {
	ctx    context.Context
	events chan event
}

func (s *sink) emit(e event) {
	e.At = time.Now().UTC().Format(time.RFC3339Nano)
	select {
	case s.events <- e:
	case <-s.ctx.Done():
	}
}
func (s *sink) diagnostic(code, detail string) {
	e := event{Type: "diagnostic", At: time.Now().UTC().Format(time.RFC3339Nano), Code: code, Detail: detail}
	select {
	case s.events <- e:
	default:
	} // Transport must not block on verbose diagnostics.
}

type handler struct{ sink *sink }

func (h handler) Enabled(context.Context, slog.Level) bool { return true }
func (h handler) Handle(_ context.Context, r slog.Record) error {
	// Only protocol diagnostics; never log keys, arbitrary packet bytes or private input.
	if strings.Contains(r.Message, "WHOAREYOU") || strings.Contains(r.Message, "FINDNODE") || strings.Contains(r.Message, "NODES") || strings.Contains(r.Message, "Bad discv5") || strings.Contains(r.Message, "Invalid") || strings.Contains(r.Message, "Unsolicited") {
		var fields []string
		r.Attrs(func(a slog.Attr) bool {
			if a.Key == "id" || a.Key == "addr" || a.Key == "err" || a.Key == "n" {
				fields = append(fields, a.Key+"="+a.Value.String())
			}
			return true
		})
		detail := r.Message + " " + strings.Join(fields, " ")
		if len(detail) > 640 {
			detail = detail[:640]
		}
		code := "PROTOCOL"
		switch {
		case strings.Contains(detail, "invalid id"):
			code = "IDENTITY_PROOF_FAILURE"
		case strings.Contains(detail, "Invalid record"):
			code = "INVALID_ENR"
		case strings.Contains(detail, "Bad discv5"):
			code = "INVALID_PACKET"
		case strings.Contains(detail, "Unsolicited"):
			code = "CORRELATION_FAILURE"
		case strings.HasPrefix(r.Message, ">> WHOAREYOU"):
			code = "WHOAREYOU_SENT"
		case strings.HasPrefix(r.Message, "<< WHOAREYOU"):
			code = "WHOAREYOU_RECEIVED"
		case strings.HasPrefix(r.Message, ">> FINDNODE"):
			code = "FINDNODE_SENT"
		case strings.HasPrefix(r.Message, "<< NODES"):
			code = "NODES_RECEIVED"
		}
		h.sink.diagnostic(code, detail)
	}
	return nil
}
func (h handler) WithAttrs([]slog.Attr) slog.Handler { return h }
func (h handler) WithGroup(string) slog.Handler      { return h }

// Strict IPv4-only ingress, global work budget, bounded exact-packet replay window.
// This also bounds live upstream challenges (one-second lifetime) under spoofed floods.
type guardedConn struct {
	*net.UDPConn
	mu     sync.Mutex
	second int64
	count  int
	replay map[[32]byte]int64
}

func (c *guardedConn) ReadFromUDPAddrPort(buf []byte) (int, netip.AddrPort, error) {
	// Read full datagrams so an oversized datagram cannot be accepted by truncation.
	var packet [1281]byte
	for {
		n, addr, err := c.UDPConn.ReadFromUDPAddrPort(packet[:])
		if err != nil {
			return 0, addr, err
		}
		if n < 63 || n > 1280 || !addr.Addr().Is4() {
			continue
		}
		now := time.Now().Unix()
		c.mu.Lock()
		if c.second != now {
			c.second = now
			c.count = 0
		}
		c.count++
		allowed := c.count <= 128
		hash := sha256.Sum256(packet[:n])
		_, duplicate := c.replay[hash]
		if allowed && !duplicate {
			if len(c.replay) >= 4096 {
				for k, at := range c.replay {
					if at < now-60 {
						delete(c.replay, k)
					}
				}
				if len(c.replay) >= 4096 {
					for k := range c.replay {
						delete(c.replay, k)
						break
					}
				}
			}
			c.replay[hash] = now
		}
		c.mu.Unlock()
		if !allowed || duplicate {
			continue
		}
		copy(buf, packet[:n])
		return n, addr, nil
	}
}
func (c *guardedConn) WriteToUDPAddrPort(buf []byte, addr netip.AddrPort) (int, error) {
	if !addr.Addr().Is4() || len(buf) > 1280 {
		return 0, errors.New("IPv4/packet policy")
	}
	return c.UDPConn.WriteToUDPAddrPort(buf, addr)
}

func parseBootstraps(texts []string) ([]*enode.Node, error) {
	if len(texts) == 0 || len(texts) > 32 {
		return nil, errors.New("require 1..32 configured bootstrap ENRs")
	}
	var nodes []*enode.Node
	for _, text := range texts {
		if !strings.HasPrefix(text, "enr:") {
			return nil, errors.New("bootstrap must be a signed ENR")
		}
		n, err := enode.Parse(enode.ValidSchemes, text)
		if err != nil {
			return nil, fmt.Errorf("invalid bootstrap ENR: %w", err)
		}
		if _, ok := ipv4Endpoint(n); !ok {
			return nil, errors.New("bootstrap requires advertised IPv4 UDP")
		}
		nodes = append(nodes, n)
	}
	return nodes, nil
}
func rawEvent(n *enode.Node, provenance string, authenticated bool) (event, error) {
	raw, err := rlp.EncodeToBytes(n.Record())
	if err != nil {
		return event{}, err
	}
	endpoint := ""
	if ep, ok := ipv4Endpoint(n); ok {
		endpoint = ep.String()
	}
	return event{Type: "node", NodeID: hex.EncodeToString(crypto.FromECDSAPub(n.Pubkey())[1:]), RLP: hex.EncodeToString(raw), Endpoint: endpoint, Provenance: provenance, Authenticated: authenticated}, nil
}

func run(ctx context.Context, cfg config, s *sink) error {
	if cfg.DurationSeconds < 0 || cfg.DurationSeconds > 3600 {
		return errors.New("runtime duration out of bounds")
	}
	key, err := crypto.HexToECDSA(cfg.PrivateKey)
	if err != nil {
		return errors.New("invalid local identity")
	}
	boots, err := parseBootstraps(cfg.Bootstraps)
	if err != nil {
		return err
	}
	db, err := enode.OpenDB("")
	if err != nil {
		return err
	}
	defer db.Close()
	ln := enode.NewLocalNode(db, key)
	// Disable geth endpoint prediction, including PONG endpoint votes. An unspecified
	// static IP removes the ENR address; no UDP/TCP claim is generated for unknown reachability.
	ln.SetStaticIP(net.IPv4zero)
	ln.SetStaticIP(net.IPv6zero)
	s.emit(event{Type: "ready", NodeID: hex.EncodeToString(crypto.FromECDSAPub(&key.PublicKey)[1:]), Detail: ln.Node().String()})
	for ctx.Err() == nil {
		conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero})
		if err != nil {
			return err
		}
		guard := &guardedConn{UDPConn: conn, replay: make(map[[32]byte]int64)}
		restrict, _ := netutil.ParseNetlist("0.0.0.0/0")
		transport, err := discover.ListenV5(guard, ln, discover.Config{PrivateKey: key, Bootnodes: boots, NetRestrict: restrict, Log: log.NewLogger(handler{s}), V5RespTimeout: 700 * time.Millisecond})
		if err != nil {
			conn.Close()
			return err
		}
		client, err := newIPv4Client(ln, key)
		if err != nil {
			transport.Close()
			return err
		}
		epoch, stopEpoch := context.WithTimeout(ctx, 10*time.Minute)
		done := make(chan struct{})
		go func() {
			select {
			case <-epoch.Done():
				client.close()
				transport.Close()
			case <-done:
			}
		}()
		crawl(epoch, transport, client, boots, s)
		close(done)
		client.close()
		stopEpoch()
		transport.Close()
		if ctx.Err() == nil {
			s.diagnostic("SESSION_RECYCLE", "Ten-minute session lifetime reached; fresh sessions required")
		}
	}
	return nil
}
func crawl(ctx context.Context, t *discover.UDPv5, client *ipv4Client, boots []*enode.Node, s *sink) {
	frontier := append([]*enode.Node(nil), boots...)
	seen := make(map[enode.ID]string)
	for ctx.Err() == nil {
		target := enode.ID{}
		copy(target[:], crypto.Keccak256([]byte(time.Now().UTC().String())))
		asked := make(map[enode.ID]bool)
		for queries := 0; queries < 64 && ctx.Err() == nil; queries++ {
			sort.Slice(frontier, func(i, j int) bool { return enode.DistCmp(target, frontier[i].ID(), frontier[j].ID()) < 0 })
			var peer *enode.Node
			for _, n := range frontier {
				if !asked[n.ID()] {
					peer = n
					break
				}
			}
			if peer == nil {
				break
			}
			asked[peer.ID()] = true
			fallback := !peer.IPAddr().Is4()
			var pingError error
			if fallback {
				pingError = client.ping(ctx, peer, t.Self().Seq())
			} else {
				_, pingError = t.Ping(peer)
			}
			if pingError != nil {
				s.diagnostic("PEER_TIMEOUT", peer.ID().String()+": "+pingError.Error())
				continue
			}
			var record *enode.Node
			var err error
			if fallback {
				var records []*enode.Node
				records, err = client.findnode(ctx, peer, []uint{0})
				if err == nil && len(records) == 1 {
					record = records[0]
				} else if err == nil {
					err = errors.New("malformed distance-zero NODES")
				}
			} else {
				record, err = t.RequestENR(peer)
			}
			if err == nil {
				if record.ID() != peer.ID() {
					s.diagnostic("IDENTITY_MISMATCH", peer.ID().String())
					continue
				}
				emitNode(s, seen, record, "discv5 authenticated PING/PONG and FINDNODE distance 0 at "+peerEndpoint(peer), true)
			} else {
				s.diagnostic("ENR_REQUEST_FAILURE", err.Error())
			}
			distance := uint(enode.LogDist(peer.ID(), target))
			if distance == 0 {
				distance = 1
			}
			distances := []uint{distance}
			if distance < 256 {
				distances = append(distances, distance+1)
			}
			if distance > 1 {
				distances = append(distances, distance-1)
			}
			var nodes []*enode.Node
			if fallback {
				nodes, err = client.findnode(ctx, peer, distances)
			} else {
				nodes, err = t.Findnode(peer, distances)
			}
			if err != nil {
				s.diagnostic("FINDNODE_TIMEOUT", peer.ID().String()+": "+err.Error())
			}
			for _, n := range nodes {
				emitNode(s, seen, n, "discv5 NODES from authenticated peer "+peer.ID().String()+" at "+peerEndpoint(peer)+"; advertised endpoint; returned-node session not asserted", false)
				if n.IPAddr().Is4() {
					t.AddKnownNode(n)
				}
				if _, ok := ipv4Endpoint(n); !ok {
					continue
				}
				duplicate := false
				for i, current := range frontier {
					if current.ID() == n.ID() {
						duplicate = true
						if n.Seq() > current.Seq() {
							frontier[i] = n
						}
						break
					}
				}
				if !duplicate && len(frontier) < 256 {
					frontier = append(frontier, n)
				}
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(10 * time.Second):
		}
	}
}
func emitNode(s *sink, seen map[enode.ID]string, n *enode.Node, provenance string, auth bool) {
	e, err := rawEvent(n, provenance, auth)
	if err != nil {
		return
	}
	token := e.RLP + fmt.Sprint(auth)
	if seen[n.ID()] == token {
		return
	}
	if len(seen) >= 4096 {
		for k := range seen {
			delete(seen, k)
			break
		}
	}
	seen[n.ID()] = token
	s.emit(e)
}
func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	decoder := json.NewDecoder(io.LimitReader(os.Stdin, 65537))
	var cfg config
	if err := decoder.Decode(&cfg); err != nil {
		fmt.Fprintln(os.Stderr, "Invalid discovery configuration")
		os.Exit(2)
	}
	if cfg.DurationSeconds > 0 {
		var stop context.CancelFunc
		ctx, stop = context.WithTimeout(ctx, time.Duration(cfg.DurationSeconds)*time.Second)
		defer stop()
	}
	s := &sink{ctx: ctx, events: make(chan event, 256)}
	writerDone := make(chan struct{})
	go func() {
		defer close(writerDone)
		out := bufio.NewWriter(os.Stdout)
		enc := json.NewEncoder(out)
		for e := range s.events {
			if enc.Encode(e) != nil || out.Flush() != nil {
				cancel()
				return
			}
		}
	}()
	err := run(ctx, cfg, s)
	close(s.events)
	<-writerDone
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func peerEndpoint(n *enode.Node) string { ep, _ := ipv4Endpoint(n); return ep.String() }
