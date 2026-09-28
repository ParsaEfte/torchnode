package main

import (
	"context"
	"encoding/hex"
	"math/big"
	"net"
	"testing"
	"time"

	"github.com/ethereum/go-ethereum/common/mclock"
	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/p2p/discover/v5wire"
)

// Real encrypted replies exercise the adapter's correlation and aggregate bounds,
// rather than bypassing the codec with pre-decoded test messages.
func TestIpv4AdapterRejectsUnsolicitedAndExcessiveResponses(t *testing.T) {
	for _, mode := range []string{"wrong-request", "excessive-total", "inconsistent-total", "multi-packet"} {
		t.Run(mode, func(t *testing.T) {
			socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
			if err != nil {
				t.Fatal(err)
			}
			defer socket.Close()
			peer, pdb := local(t, 2, socket.LocalAddr().(*net.UDPAddr).Port)
			defer pdb.Close()
			own, odb := local(t, 1, 32001)
			defer odb.Close()
			key, _ := crypto.ToECDSA(big.NewInt(1).FillBytes(make([]byte, 32)))
			peerKey, _ := crypto.ToECDSA(big.NewInt(2).FillBytes(make([]byte, 32)))
			client, err := newIPv4Client(own, key)
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

func TestCancellationDuringOutstandingHandshakeAndSilentPeerTimeout(t *testing.T) {
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	peer, pdb := local(t, 2, socket.LocalAddr().(*net.UDPAddr).Port)
	defer pdb.Close()
	own, odb := local(t, 1, 32001)
	defer odb.Close()
	key, _ := crypto.ToECDSA(big.NewInt(1).FillBytes(make([]byte, 32)))
	client, err := newIPv4Client(own, key)
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
