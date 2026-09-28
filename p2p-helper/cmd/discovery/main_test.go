package main

import (
	"bytes"
	"context"
	"encoding/hex"
	"math/big"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/ethereum/go-ethereum/common/mclock"
	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/p2p/discover"
	"github.com/ethereum/go-ethereum/p2p/discover/v5wire"
	"github.com/ethereum/go-ethereum/p2p/enode"
	"github.com/ethereum/go-ethereum/p2p/enr"
)

func local(t *testing.T, index int, port int) (*enode.LocalNode, *enode.DB) {
	t.Helper()
	key, _ := crypto.ToECDSA(big.NewInt(int64(index)).FillBytes(make([]byte, 32)))
	db, err := enode.OpenDB("")
	if err != nil {
		t.Fatal(err)
	}
	ln := enode.NewLocalNode(db, key)
	ln.SetStaticIP(net.IPv4(127, 0, 0, 1))
	ln.SetFallbackUDP(port)
	ln.Set(enr.IPv6(net.IPv6loopback))
	ln.Set(enr.TCP(30305))
	ln.Set(enr.WithEntry("vendor", []byte("unknown evidence")))
	return ln, db
}
func server(t *testing.T, index int) (*discover.UDPv5, *enode.LocalNode) {
	t.Helper()
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	ln, db := local(t, index, conn.LocalAddr().(*net.UDPAddr).Port)
	key, _ := crypto.ToECDSA(big.NewInt(int64(index)).FillBytes(make([]byte, 32)))
	transport, err := discover.ListenV5(conn, ln, discover.Config{PrivateKey: key, NoFindnodeLivenessCheck: true})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { transport.Close(); db.Close() })
	return transport, ln
}
func TestAuthenticatedLoopbackSessionReuseAndMultiPacketNodes(t *testing.T) {
	peer, pln := server(t, 2)
	client, cln := server(t, 1)
	for i := 3; i < 19; i++ {
		ln, db := local(t, i, 32000+i)
		peer.AddKnownNode(ln.Node())
		db.Close()
	}
	for i := 0; i < 2; i++ {
		pong, err := client.Ping(pln.Node())
		if err != nil || pong.ENRSeq != pln.Node().Seq() {
			t.Fatalf("authenticated PONG: %v", err)
		}
	}
	record, err := client.RequestENR(pln.Node())
	if err != nil || record.ID() != pln.ID() {
		t.Fatalf("distance zero ENR: %v", err)
	}
	var distances []uint
	for i := uint(1); i <= 256; i++ {
		distances = append(distances, i)
	}
	nodes, err := client.Findnode(pln.Node(), distances)
	if err != nil || len(nodes) < 8 {
		t.Fatalf("multi-packet NODES: got %d, %v", len(nodes), err)
	}
	if cln.ID() == pln.ID() {
		t.Fatal("identities merged")
	}
	e, err := rawEvent(record, "loopback authenticated", true)
	if err != nil || e.RLP == "" || !e.Authenticated || strings.Contains(e.NodeID, "0x") {
		t.Fatal("lossless event")
	}
}
func TestHelperSharesSuppliedIdentityAndDiscoversBeyondBootstrap(t *testing.T) {
	peer, ln := server(t, 2)
	for i := 3; i < 15; i++ {
		n, db := local(t, i, 32000+i)
		peer.AddKnownNode(n.Node())
		db.Close()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	s := &sink{ctx: ctx, events: make(chan event, 256)}
	done := make(chan error, 1)
	go func() {
		done <- run(ctx, config{PrivateKey: hex.EncodeToString(big.NewInt(1).FillBytes(make([]byte, 32))), Bootstraps: []string{ln.Node().String()}}, s)
	}()
	ids := map[string]bool{}
	challenge := false
	authenticated := false
	ready := false
	for !ready || !challenge || !authenticated || len(ids) < 2 {
		select {
		case e := <-s.events:
			if e.Type == "ready" {
				key, _ := crypto.ToECDSA(big.NewInt(1).FillBytes(make([]byte, 32)))
				if e.NodeID != hex.EncodeToString(crypto.FromECDSAPub(&key.PublicKey)[1:]) {
					t.Fatal("local identity not shared")
				}
				n, err := enode.Parse(enode.ValidSchemes, e.Detail)
				if err != nil || n.IPAddr().IsValid() || n.UDP() != 0 {
					t.Fatal("fabricated local endpoint")
				}
				ready = true
			}
			if strings.Contains(e.Detail, "WHOAREYOU") {
				challenge = true
			}
			if e.Type == "node" {
				ids[e.NodeID] = true
				authenticated = authenticated || e.Authenticated
			}
		case <-ctx.Done():
			t.Fatalf("did not discover routing peers: ready=%v challenge=%v session=%v nodes=%d", ready, challenge, authenticated, len(ids))
		}
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("shutdown leak")
	}
}
func TestGuardRejectsOversizedUndersizedReplayAndIpv6(t *testing.T) {
	conn, _ := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	defer conn.Close()
	guard := &guardedConn{UDPConn: conn, replay: make(map[[32]byte]int64)}
	sender, _ := net.ListenUDP("udp4", nil)
	defer sender.Close()
	for _, size := range []int{1, 62, 1281, 1500, 63, 63, 64} {
		packet := bytes.Repeat([]byte{byte(size)}, size)
		sender.WriteToUDP(packet, conn.LocalAddr().(*net.UDPAddr))
	}
	conn.SetReadDeadline(time.Now().Add(time.Second))
	var buf [1280]byte
	n, _, err := guard.ReadFromUDPAddrPort(buf[:])
	if err != nil || n != 63 {
		t.Fatal(n, err)
	}
	n, _, err = guard.ReadFromUDPAddrPort(buf[:])
	if err != nil || n != 64 {
		t.Fatal("replay accepted", n, err)
	}
	if _, err = guard.WriteToUDPAddrPort(buf[:64], netip.MustParseAddrPort("[::1]:9000")); err == nil {
		t.Fatal("IPv6 send enabled")
	}
	if len(guard.replay) > 4096 {
		t.Fatal("unbounded replay state")
	}
}
func TestMalformedBootstrapAndLocalInputAreRejected(t *testing.T) {
	for _, boots := range [][]string{nil, {"bad"}, make([]string, 33)} {
		if _, err := parseBootstraps(boots); err == nil {
			t.Fatal("accepted invalid bootstraps")
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s := &sink{ctx: ctx, events: make(chan event, 8)}
	if err := run(ctx, config{PrivateKey: "private input must not be emitted"}, s); err == nil || strings.Contains(err.Error(), "private input") {
		t.Fatal("secret in failure")
	}
}
func TestCodecHandshakeIdentityProofAndTagFailures(t *testing.T) {
	a, adb := local(t, 1, 32001)
	defer adb.Close()
	b, bdb := local(t, 2, 32002)
	defer bdb.Close()
	ka, _ := crypto.ToECDSA(big.NewInt(1).FillBytes(make([]byte, 32)))
	kb, _ := crypto.ToECDSA(big.NewInt(2).FillBytes(make([]byte, 32)))
	ca := v5wire.NewCodec(a, ka, mclock.System{}, nil)
	cb := v5wire.NewCodec(b, kb, mclock.System{}, nil)
	first, nonce, err := ca.Encode(b.ID(), "b", &v5wire.Ping{ReqID: []byte{1}}, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, _, unknown, err := cb.Decode(first, "a")
	if err != nil || unknown.Kind() != v5wire.UnknownPacket {
		t.Fatal("initial packet", err)
	}
	challenge := &v5wire.Whoareyou{Nonce: nonce, IDNonce: [16]byte{1}, RecordSeq: 0}
	wire, _, err := cb.Encode(a.ID(), "a", challenge, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, _, packet, err := ca.Decode(wire, "b")
	if err != nil {
		t.Fatal(err)
	}
	proof := packet.(*v5wire.Whoareyou)
	proof.Node = b.Node()
	handshake, _, err := ca.Encode(b.ID(), "b", &v5wire.Ping{ReqID: []byte{1}}, proof)
	if err != nil {
		t.Fatal(err)
	}
	corrupted := append([]byte(nil), handshake...)
	corrupted[len(corrupted)-1] ^= 1
	if _, _, _, err = cb.Decode(corrupted, "a"); err == nil {
		t.Fatal("invalid auth tag accepted")
	}
	// A rejected handshake consumes its challenge, as required for replay safety. Start fresh.
	cb = v5wire.NewCodec(b, kb, mclock.System{}, nil)
	challenge = &v5wire.Whoareyou{Nonce: nonce, IDNonce: [16]byte{2}, RecordSeq: 0}
	wire, _, err = cb.Encode(a.ID(), "a", challenge, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, _, packet, err = ca.Decode(wire, "b")
	if err != nil {
		t.Fatal(err)
	}
	proof = packet.(*v5wire.Whoareyou)
	proof.Node = b.Node()
	handshake, _, err = ca.Encode(b.ID(), "b", &v5wire.Ping{ReqID: []byte{1}}, proof)
	if err != nil {
		t.Fatal(err)
	}
	id, node, ping, err := cb.Decode(handshake, "a")
	if err != nil || id != a.ID() || node.ID() != a.ID() || ping.Kind() != v5wire.PingMsg {
		t.Fatal("handshake", err)
	}
	if _, _, _, err = cb.Decode(handshake, "a"); err == nil {
		t.Fatal("handshake replay accepted")
	}
	reply, _, err := cb.Encode(a.ID(), "a", &v5wire.Pong{ReqID: []byte{1}}, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, _, pong, err := ca.Decode(reply, "b")
	if err != nil || pong.Kind() != v5wire.PongMsg {
		t.Fatal("session response", err)
	}
	for _, bad := range [][]byte{nil, make([]byte, 62), make([]byte, 1281)} {
		if _, _, _, err = ca.Decode(bad, "b"); err == nil {
			t.Fatal("malformed packet accepted")
		}
	}
}

func TestGlobalIpv6EnrStillUsesIpv4LoopbackTransport(t *testing.T) {
	peer, ln := server(t, 2)
	ln.SetStaticIP(net.ParseIP("2606:4700:4700::1111"))
	target := ln.Node()
	if !target.IPAddr().Is6() {
		t.Fatal("fixture must exercise upstream IPv6 preference")
	}
	ep, ok := ipv4Endpoint(target)
	if !ok || ep.Addr() != netip.MustParseAddr("127.0.0.1") {
		t.Fatal("did not select signed IPv4 tuple")
	}
	own, db := local(t, 1, 32001)
	defer db.Close()
	key, _ := crypto.ToECDSA(big.NewInt(1).FillBytes(make([]byte, 32)))
	client, err := newIPv4Client(own, key)
	if err != nil {
		t.Fatal(err)
	}
	defer client.close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err = client.ping(ctx, target, own.Node().Seq()); err != nil {
		t.Fatal("IPv4-only authenticated session", err)
	}
	records, err := client.findnode(ctx, target, []uint{0})
	if err != nil || len(records) != 1 {
		t.Fatal("IPv4 FINDNODE/NODES", err)
	}
	var ip6 enr.IPv6
	if records[0].Load(&ip6) != nil || !net.IP(ip6).Equal(net.ParseIP("2606:4700:4700::1111")) {
		t.Fatal("passive IPv6 was discarded")
	}
	if client.conn.LocalAddr().(*net.UDPAddr).IP.To4() == nil {
		t.Fatal("IPv6 socket enabled")
	}
	peer.Close()
}
func TestIpv4AdapterCancellationAndInvalidDistances(t *testing.T) {
	own, db := local(t, 1, 32001)
	defer db.Close()
	key, _ := crypto.ToECDSA(big.NewInt(1).FillBytes(make([]byte, 32)))
	client, err := newIPv4Client(own, key)
	if err != nil {
		t.Fatal(err)
	}
	defer client.close()
	target, tdb := local(t, 2, 32002)
	defer tdb.Close()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err = client.ping(ctx, target.Node(), 1); err == nil {
		t.Fatal("cancel ignored")
	}
	if _, err = client.findnode(context.Background(), target.Node(), []uint{257}); err == nil {
		t.Fatal("invalid distance accepted")
	}
}
