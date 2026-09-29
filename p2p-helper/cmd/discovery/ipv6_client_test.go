package main

import (
	"context"
	"encoding/hex"
	"github.com/ethereum/go-ethereum/p2p/discover"
	"github.com/ethereum/go-ethereum/p2p/enode"
	"math/big"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/ethereum/go-ethereum/common/mclock"
	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/p2p/discover/v5wire"
	"github.com/ethereum/go-ethereum/p2p/enr"
)

// Real encrypted replies exercise the adapter's correlation and aggregate bounds,
// rather than bypassing the codec with pre-decoded test messages.
func TestIpv6AdapterRejectsUnsolicitedAndExcessiveResponses(t *testing.T) {
	for _, mode := range []string{"wrong-request", "excessive-total", "inconsistent-total", "multi-packet"} {
		t.Run(mode, func(t *testing.T) {
			socket, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6loopback})
			if err != nil {
				t.Fatal(err)
			}
			defer socket.Close()
			peer, pdb := local(t, 2, socket.LocalAddr().(*net.UDPAddr).Port)
			defer pdb.Close()
			peer.SetStaticIP(net.IPv4zero)
			peer.SetStaticIP(net.IPv6loopback)
			peer.Set(enr.UDP6(socket.LocalAddr().(*net.UDPAddr).Port))
			own, odb := local(t, 1, 32001)
			defer odb.Close()
			key, _ := crypto.ToECDSA(big.NewInt(1).FillBytes(make([]byte, 32)))
			peerKey, _ := crypto.ToECDSA(big.NewInt(2).FillBytes(make([]byte, 32)))
			client, err := newFamilyClient(own, key, true)
			if err != nil {
				t.Fatal(err)
			}
			defer client.close()
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			done := make(chan error, 1)
			go func() {
				codec := v5wire.NewCodec(peer, peerKey, mclock.System{}, nil)
				var buf [1280]byte
				socket.SetReadDeadline(time.Now().Add(2 * time.Second))
				for {
					size, addr, e := socket.ReadFromUDPAddrPort(buf[:])
					if e != nil {
						done <- e
						return
					}
					id, _, p, e := codec.Decode(buf[:size], addr.String())
					if e != nil {
						done <- e
						return
					}
					send := func(answer v5wire.Packet) error {
						wire, _, e := codec.Encode(id, addr.String(), answer, nil)
						if e != nil {
							return e
						}
						_, e = socket.WriteToUDPAddrPort(wire, addr)
						return e
					}
					if u, ok := p.(*v5wire.Unknown); ok {
						e = send(&v5wire.Whoareyou{Nonce: u.Nonce, IDNonce: [16]byte{1}, RecordSeq: 0})
						if e != nil {
							done <- e
							return
						}
						continue
					}
					query, ok := p.(*v5wire.Findnode)
					if !ok {
						done <- nil
						return
					}
					switch mode {
					case "wrong-request":
						e = send(&v5wire.Nodes{ReqID: []byte{42}, RespCount: 1})
					case "excessive-total":
						e = send(&v5wire.Nodes{ReqID: query.ReqID, RespCount: 6})
					case "inconsistent-total":
						e = send(&v5wire.Nodes{ReqID: query.ReqID, RespCount: 2})
						if e == nil {
							e = send(&v5wire.Nodes{ReqID: query.ReqID, RespCount: 3})
						}
					case "multi-packet":
						// Different ciphertext nonces: both responses belong to the same request.
						e = send(&v5wire.Nodes{ReqID: query.ReqID, RespCount: 2})
						if e == nil {
							e = send(&v5wire.Nodes{ReqID: query.ReqID, RespCount: 2})
						}
					}
					done <- e
					return
				}
			}()
			nodes, err := client.findnode(ctx, peer.Node(), []uint{0})
			if mode == "multi-packet" {
				if err != nil || len(nodes) != 0 {
					t.Fatalf("multi-packet aggregation: %v", err)
				}
			} else if err == nil {
				t.Fatal("hostile response accepted")
			}
			if e := <-done; e != nil {
				t.Fatal(e)
			}
		})
	}
}

func TestIpv6CancellationDuringOutstandingHandshakeAndSilentPeerTimeout(t *testing.T) {
	socket, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	peer, pdb := local(t, 2, socket.LocalAddr().(*net.UDPAddr).Port)
	defer pdb.Close()
	peer.SetStaticIP(net.IPv4zero)
	peer.SetStaticIP(net.IPv6loopback)
	peer.Set(enr.UDP6(socket.LocalAddr().(*net.UDPAddr).Port))
	own, odb := local(t, 1, 32001)
	defer odb.Close()
	key, _ := crypto.ToECDSA(big.NewInt(1).FillBytes(make([]byte, 32)))
	client, err := newFamilyClient(own, key, true)
	if err != nil {
		t.Fatal(err)
	}
	defer client.close()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- client.ping(ctx, peer.Node(), 1) }()
	var buf [1280]byte
	socket.SetReadDeadline(time.Now().Add(time.Second))
	if _, _, err = socket.ReadFromUDPAddrPort(buf[:]); err != nil {
		t.Fatal(err)
	}
	cancel()
	select {
	case err = <-done:
		if err != context.Canceled {
			t.Fatalf("cancellation: %v", err)
		}
	case <-time.After(200 * time.Millisecond):
		t.Fatal("pending handshake did not cancel")
	}
	began := time.Now()
	if err = client.ping(context.Background(), peer.Node(), 1); err == nil || time.Since(began) > time.Second {
		t.Fatalf("request timeout bound: %v", err)
	}
	// Drain that timed-out request before observing the engine's initial packet.
	if _, _, err = socket.ReadFromUDPAddrPort(buf[:]); err != nil {
		t.Fatal(err)
	}
	socket.SetReadDeadline(time.Now().Add(time.Second))
	// The production engine also exits when cancellation interrupts an initial session.
	engineCtx, stop := context.WithCancel(context.Background())
	s := &sink{ctx: engineCtx, events: make(chan event, 256)}
	go func() {
		done <- run(engineCtx, config{PrivateKey: hex.EncodeToString(big.NewInt(1).FillBytes(make([]byte, 32))), Bootstraps: []string{peer.Node().String()}}, s)
	}()
	if _, _, err = socket.ReadFromUDPAddrPort(buf[:]); err != nil {
		t.Fatal(err)
	}
	stop()
	select {
	case err = <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("engine leaked outstanding handshake")
	}
}

func ipv6Server(t *testing.T, index int) (*discover.UDPv5, *enode.LocalNode) {
	t.Helper()
	socket, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6loopback})
	if err != nil {
		t.Fatal(err)
	}
	ln, db := local(t, index, socket.LocalAddr().(*net.UDPAddr).Port)
	ln.SetStaticIP(net.IPv4zero)
	ln.SetStaticIP(net.IPv6loopback)
	ln.Set(enr.UDP6(socket.LocalAddr().(*net.UDPAddr).Port))
	key, _ := crypto.ToECDSA(big.NewInt(int64(index)).FillBytes(make([]byte, 32)))
	transport, err := discover.ListenV5(socket, ln, discover.Config{PrivateKey: key, NoFindnodeLivenessCheck: true})
	if err != nil {
		socket.Close()
		db.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { transport.Close(); db.Close() })
	return transport, ln
}
func TestIPv6AuthenticatedSessionPingPongFindnodeAndShutdown(t *testing.T) {
	peer, ln := ipv6Server(t, 2)
	for i := 3; i < 16; i++ {
		candidate, db := local(t, i, 32000+i)
		candidate.SetStaticIP(net.IPv4zero)
		candidate.SetStaticIP(net.IPv6loopback)
		candidate.Set(enr.UDP6(32000 + i))
		peer.AddKnownNode(candidate.Node())
		db.Close()
	}
	own, db := local(t, 1, 32001)
	defer db.Close()
	key, _ := crypto.ToECDSA(big.NewInt(1).FillBytes(make([]byte, 32)))
	client, err := newFamilyClient(own, key, true)
	if err != nil {
		t.Fatal(err)
	}
	defer client.close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for i := 0; i < 2; i++ {
		if err = client.ping(ctx, ln.Node(), own.Node().Seq()); err != nil {
			t.Fatal(err)
		}
	}
	records, err := client.findnode(ctx, ln.Node(), []uint{0})
	if err != nil || len(records) != 1 {
		t.Fatalf("IPv6 ENR: %d %v", len(records), err)
	}
	var distances []uint
	for d := uint(1); d <= 256; d++ {
		distances = append(distances, d)
	}
	records, err = client.findnode(ctx, ln.Node(), distances)
	if err != nil || len(records) < 8 {
		t.Fatalf("IPv6 multi-packet routing: %d %v", len(records), err)
	}
	// Production crawl, not just the test client, must select the IPv6 tuple and authenticate.
	runCtx, stop := context.WithTimeout(context.Background(), 3*time.Second)
	sink := &sink{ctx: runCtx, events: make(chan event, 256)}
	done := make(chan error, 1)
	go func() {
		done <- run(runCtx, config{PrivateKey: hex.EncodeToString(big.NewInt(1).FillBytes(make([]byte, 32))), Bootstraps: []string{ln.Node().String()}}, sink)
	}()
	authenticated, challenge := false, false
	for !authenticated || !challenge {
		select {
		case e := <-sink.events:
			if e.Code == "WHOAREYOU_RECEIVED" && strings.HasPrefix(e.Detail, "[::1]:") {
				challenge = true
			}
			if e.Type == "node" && e.Authenticated && strings.Contains(e.Provenance, "[::1]:") {
				authenticated = true
			}
		case <-runCtx.Done():
			t.Fatal("production IPv6 session was not demonstrated")
		}
	}
	stop()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("IPv6 shutdown leak")
	}
}

func TestDualStackTupleSelection(t *testing.T) {
	_, ln := ipv6Server(t, 2)
	ln.SetStaticIP(net.IPv4(127, 0, 0, 1))
	ln.Set(enr.UDP(30303))
	four, ok4 := ipv4Endpoint(ln.Node())
	six, ok6 := ipv6Endpoint(ln.Node())
	if !ok4 || !ok6 || !four.Addr().Is4() || !six.Addr().Is6() || four.Port() != 30303 {
		t.Fatal("lost independent signed tuples")
	}
}

func TestDualStackCrawlRestartAndIPv6FailureIsolation(t *testing.T) {
	_, ln := ipv6Server(t, 2)
	ep6, _ := ipv6Endpoint(ln.Node())
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(ep6.Port())})
	if err != nil {
		t.Fatal(err)
	}
	ln.SetStaticIP(net.IPv4(127, 0, 0, 1))
	ln.SetFallbackUDP(socket.LocalAddr().(*net.UDPAddr).Port)
	key, _ := crypto.ToECDSA(big.NewInt(2).FillBytes(make([]byte, 32)))
	server4, err := discover.ListenV5(socket, ln, discover.Config{PrivateKey: key})
	if err != nil {
		t.Fatal(err)
	}
	defer server4.Close()
	for iteration := 0; iteration < 3; iteration++ {
		if iteration == 2 {
			ln.SetStaticIP(net.ParseIP("2001:db8::1"))
		}
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		sink := &sink{ctx: ctx, events: make(chan event, 256)}
		done := make(chan error, 1)
		go func() {
			done <- run(ctx, config{PrivateKey: hex.EncodeToString(big.NewInt(1).FillBytes(make([]byte, 32))), Bootstraps: []string{ln.Node().String()}}, sink)
		}()
		four, six := false, false
		for !four || !six {
			select {
			case e := <-sink.events:
				four = four || e.Code == "ENDPOINT_SUCCESS" && e.AddressFamily == "IPV4"
				expected := "ENDPOINT_SUCCESS"
				if iteration == 2 {
					expected = "ENDPOINT_FAILURE"
				}
				six = six || e.Code == expected && e.AddressFamily == "IPV6"
			case <-ctx.Done():
				cancel()
				t.Fatalf("dual-stack iteration %d: IPv4=%v IPv6=%v", iteration, four, six)
			}
		}
		cancel()
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
		case <-time.After(time.Second):
			t.Fatal("restart leaked transport")
		}
	}
}

func TestIPv6GuardRejectsOversizedPacketsAndReplay(t *testing.T) {
	conn, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	sender, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6loopback})
	if err != nil {
		t.Fatal(err)
	}
	defer sender.Close()
	guard := &guardedConn{UDPConn: conn, replay: make(map[[32]byte]int64)}
	for _, size := range []int{1, 62, 1281, 1500, 63, 63, 64} {
		packet := make([]byte, size)
		for i := range packet {
			packet[i] = byte(size)
		}
		if _, err := sender.WriteToUDP(packet, conn.LocalAddr().(*net.UDPAddr)); err != nil {
			t.Fatal(err)
		}
	}
	conn.SetReadDeadline(time.Now().Add(time.Second))
	var buf [1280]byte
	for _, wanted := range []int{63, 64} {
		n, from, err := guard.ReadFromUDPAddrPort(buf[:])
		if err != nil || n != wanted || !from.Addr().Is6() {
			t.Fatalf("guard %d %v", n, err)
		}
	}
}
