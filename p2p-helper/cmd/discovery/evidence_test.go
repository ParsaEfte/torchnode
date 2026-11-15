package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math/big"
	"net"
	"os"
	"testing"
	"time"

	"github.com/ethereum/go-ethereum/common/mclock"
	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/p2p/discover/v5wire"
	"github.com/ethereum/go-ethereum/p2p/enode"
	"github.com/ethereum/go-ethereum/p2p/enr"
	"github.com/ethereum/go-ethereum/rlp"
)

func TestB3AuthenticatedCapture(t *testing.T) {
	for _, mode := range []string{"valid", "self", "empty", "repeated-enr", "multi", "duplicate", "replay", "wrong-request", "partial", "delayed", "invalid-enr", "distance-mismatch", "inconsistent", "wrong-key", "malformed"} {
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
			target, tdb := local(t, 3, 32003)
			defer tdb.Close()
			other, xdb := local(t, 4, 32003)
			defer xdb.Close() // Same endpoint, different signed identities.
			key, _ := crypto.ToECDSA(big.NewInt(1).FillBytes(make([]byte, 32)))
			peerKey, _ := crypto.ToECDSA(big.NewInt(2).FillBytes(make([]byte, 32)))
			client, err := newIPv4Client(own, key)
			if err != nil {
				t.Fatal(err)
			}
			defer client.close()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			s := &sink{ctx: ctx, events: make(chan event, 256), observer: &observerContext{InstanceID: "00000000-0000-0000-0000-000000000001", RunID: "00000000-0000-0000-0000-000000000002", KeyLifecycle: "EPHEMERAL_FIRST_SEEN"}}
			client.sink = s
			distances := []uint{uint(enode.LogDist(peer.ID(), target.ID())), uint(enode.LogDist(peer.ID(), other.ID()))}
			if mode == "self" {
				distances = []uint{0}
			}
			done := make(chan error, 1)
			go func() {
				if mode == "wrong-key" {
					peerKey, _ = crypto.ToECDSA(big.NewInt(4).FillBytes(make([]byte, 32)))
				}
				codec := v5wire.NewCodec(peer, peerKey, mclock.System{}, nil)
				var buf [1280]byte
				socket.SetReadDeadline(time.Now().Add(2500 * time.Millisecond))
				for {
					size, addr, e := socket.ReadFromUDPAddrPort(buf[:])
					if e != nil {
						done <- e
						return
					}
					id, _, p, e := codec.Decode(buf[:size], addr.String())
					if e != nil {
						if mode == "wrong-key" {
							done <- nil
						} else {
							done <- e
						}
						return
					}
					send := func(answer v5wire.Packet, replay bool, malformed bool) error {
						wire, _, e := codec.Encode(id, addr.String(), answer, nil)
						if e != nil {
							return e
						}
						if malformed {
							wire[len(wire)-1] ^= 1
						}
						_, e = socket.WriteToUDPAddrPort(wire, addr)
						if e == nil && replay {
							_, e = socket.WriteToUDPAddrPort(wire, addr)
						}
						return e
					}
					if u, ok := p.(*v5wire.Unknown); ok {
						if e = send(&v5wire.Whoareyou{Nonce: u.Nonce, IDNonce: [16]byte{1}}, false, false); e != nil {
							done <- e
							return
						}
						continue
					}
					q, ok := p.(*v5wire.Findnode)
					if !ok {
						continue
					}
					first := &v5wire.Nodes{ReqID: q.ReqID, RespCount: 1, Nodes: []*enr.Record{target.Node().Record()}}
					switch mode {
					case "self":
						first.Nodes = []*enr.Record{peer.Node().Record()}
						e = send(first, false, false)
					case "empty":
						first.Nodes = nil
						e = send(first, false, false)
					case "repeated-enr":
						first.RespCount = 2
						e = send(first, false, false)
						if e == nil {
							e = send(&v5wire.Nodes{ReqID: q.ReqID, RespCount: 2, Nodes: []*enr.Record{target.Node().Record(), other.Node().Record()}}, false, false)
						}
					case "multi", "duplicate", "replay", "partial", "inconsistent":
						first.RespCount = 2
						e = send(first, mode == "replay", false)
						if e == nil && mode == "duplicate" {
							e = send(first, false, false)
						}
						if e == nil && mode != "partial" {
							total := uint8(2)
							if mode == "inconsistent" {
								total = 3
							}
							e = send(&v5wire.Nodes{ReqID: q.ReqID, RespCount: total, Nodes: []*enr.Record{other.Node().Record()}}, false, false)
						}
					case "wrong-request":
						first.ReqID = []byte{42}
						e = send(first, false, false)
					case "delayed":
						time.Sleep(800 * time.Millisecond)
						e = send(first, false, false)
					case "invalid-enr":
						raw, _ := rlp.EncodeToBytes(target.Node().Record())
						var fields []rlp.RawValue
						_ = rlp.DecodeBytes(raw, &fields)
						var signature []byte
						_ = rlp.DecodeBytes(fields[0], &signature)
						signature[0] ^= 1
						fields[0], _ = rlp.EncodeToBytes(signature)
						raw, _ = rlp.EncodeToBytes(fields)
						var bad enr.Record
						_ = rlp.DecodeBytes(raw, &bad)
						first.Nodes = []*enr.Record{&bad, other.Node().Record()}
						e = send(first, false, false)
					case "distance-mismatch":
						first.Nodes = []*enr.Record{peer.Node().Record()}
						e = send(first, false, false)
					case "malformed":
						e = send(first, false, true)
					default:
						e = send(first, false, false)
					}
					done <- e
					return
				}
			}()
			_, requestErr := client.request(ctx, peer.Node(), &v5wire.Findnode{ReqID: []byte{1, 2, 3, 4, 5, 6, 7, 8}, Distances: distances}, v5wire.NodesMsg)
			if e := <-done; e != nil {
				t.Fatal(e)
			}
			var captured *discoveryTrace
			for len(s.events) > 0 {
				e := <-s.events
				if e.Trace != nil {
					captured = e.Trace
				}
			}
			if captured == nil {
				t.Fatal("no evidence trace")
			}
			if captured.LocalIdentity == captured.ResponderIdentity || len(captured.ResponderIdentity) != 128 || captured.Observer == nil {
				t.Fatal("missing distinct identity context")
			}
			wantComplete := mode == "self" || mode == "empty" || mode == "repeated-enr" || mode == "valid" || mode == "multi" || mode == "duplicate" || mode == "replay" || mode == "invalid-enr" || mode == "distance-mismatch"
			if (requestErr == nil) != wantComplete {
				t.Fatalf("completion: %v", requestErr)
			}
			if wantComplete && captured.Outcome != "COMPLETE" {
				t.Fatal(captured.Outcome)
			}
			if !wantComplete && captured.Outcome != "INCOMPLETE" {
				t.Fatal("incomplete trace marked complete")
			}
			if path := os.Getenv("TORCHNODE_B3_FIXTURE"); path != "" {
				raw, _ := json.Marshal(event{Type: "discoveryTrace", Trace: captured})
				if e := os.WriteFile(path+"."+mode, raw, 0600); e != nil {
					t.Fatal(e)
				}
			}
			switch mode {
			case "valid":
				if len(captured.Packets) != 1 || !captured.Packets[0].Authenticated || captured.Packets[0].Records[0].Validation != "VALID" {
					t.Fatal("not authenticated")
				}
				if path := os.Getenv("TORCHNODE_B3_FIXTURE"); path != "" {
					raw, _ := json.Marshal(event{Type: "discoveryTrace", Trace: captured})
					if e := os.WriteFile(path, raw, 0600); e != nil {
						t.Fatal(e)
					}
				}
			case "multi":
				if captured.Counts["MATCHED_NODES"] != 2 {
					t.Fatal("multipart count")
				}
			case "duplicate":
				if captured.Counts["DUPLICATE_NODES"] != 1 || captured.Counts["MATCHED_NODES"] != 2 {
					t.Fatal("duplicate counted as unique")
				}
			case "replay":
				if captured.GuardCounts["REPLAY_DROPPED"] != 1 {
					t.Fatal("silent replay drop")
				}
			case "wrong-request":
				if captured.Counts["REQUEST_ID_MISMATCH"] != 1 {
					t.Fatal("missing correlation diagnostic")
				}
			case "partial":
				if !captured.Partial || captured.FailureCode != "TIMEOUT" {
					t.Fatal("partial timeout not explicit")
				}
				if captured.Counts["MATCHED_NODES"] != 1 {
					t.Fatal("lost partial evidence")
				}
			case "invalid-enr":
				if captured.Packets[0].Records[0].Validation != "INVALID_ENR" || captured.Packets[0].Records[1].Validation != "VALID" {
					t.Fatal("individual ENR outcomes lost")
				}
			case "distance-mismatch":
				if captured.Packets[0].Records[0].Validation != "DISTANCE_MISMATCH" {
					t.Fatal("distance not checked")
				}
			}
		})
	}
}
func TestB3CaptureBoundsAndDropCounters(t *testing.T) {
	s := &sink{ctx: context.Background(), events: make(chan event, 1)}
	s.tryEmit(event{Type: "diagnostic"})
	s.tryEmit(event{Type: "diagnostic"})
	if s.dropped.Load() != 1 {
		t.Fatal("silent queue drop")
	}
	<-s.events
	s.tryEmit(event{Type: "diagnostic"})
	if (<-s.events).DroppedEvents != 1 {
		t.Fatal("counter not exposed")
	}
	trace := &discoveryTrace{Counts: make(map[string]uint64)}
	for i := 0; i < 20; i++ {
		trace.packet(make([]byte, 1280), enode.ID{}, nil, false, "DECODE_FAILED")
	}
	if len(trace.Packets) != 8 || trace.OmittedPackets != 12 || trace.Counts["DECODE_FAILED"] != 20 {
		t.Fatal("unbounded trace")
	}
	many := make([]*enr.Record, 17)
	for i := range many {
		many[i] = new(enr.Record)
	}
	bounded := &discoveryTrace{Counts: make(map[string]uint64)}
	bounded.packet(make([]byte, 1280), enode.ID{}, &v5wire.Nodes{ReqID: []byte{1}, RespCount: 1, Nodes: many}, true, "MATCHED_NODES")
	if len(bounded.Packets[0].Records) != 16 || bounded.Packets[0].OmittedRecords != 1 {
		t.Fatal("silent ENR overflow")
	}
	if validKeyLifecycle("MISSING") || validObserverID("127.0.0.1") {
		t.Fatal("missing key/endpoint accepted as observer context")
	}

}

func TestB3DifferentAuthenticatedRespondersAtSameEndpoint(t *testing.T) {
	socket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
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
	s := &sink{ctx: ctx, events: make(chan event, 256)}
	export := os.Getenv("TORCHNODE_B4_FIXTURE")
	if export != "" {
		s.observer = &observerContext{InstanceID: "00000000-0000-0000-0000-000000000001", RunID: "00000000-0000-0000-0000-000000000002", KeyLifecycle: "EPHEMERAL_FIRST_SEEN"}
	}
	target, targetDB := local(t, 3, 32003)
	defer targetDB.Close()
	client.sink = s
	var previous *discoveryTrace
	for _, index := range []int{2, 4} {
		peer, pdb := local(t, index, socket.LocalAddr().(*net.UDPAddr).Port)
		defer pdb.Close()
		private, _ := crypto.ToECDSA(big.NewInt(int64(index)).FillBytes(make([]byte, 32)))
		done := make(chan error, 1)
		go func() {
			codec := v5wire.NewCodec(peer, private, mclock.System{}, nil)
			var buf [1280]byte
			socket.SetReadDeadline(time.Now().Add(2 * time.Second))
			for {
				n, addr, e := socket.ReadFromUDPAddrPort(buf[:])
				if e != nil {
					done <- e
					return
				}
				id, _, p, e := codec.Decode(buf[:n], addr.String())
				if e != nil {
					done <- e
					return
				}
				var answer v5wire.Packet
				if u, ok := p.(*v5wire.Unknown); ok {
					answer = &v5wire.Whoareyou{Nonce: u.Nonce, IDNonce: [16]byte{1}}
				} else if q, ok := p.(*v5wire.Findnode); ok {
					answer = &v5wire.Nodes{ReqID: q.ReqID, RespCount: 1, Nodes: []*enr.Record{target.Node().Record()}}
				} else {
					continue
				}
				wire, _, e := codec.Encode(id, addr.String(), answer, nil)
				if e == nil {
					_, e = socket.WriteToUDPAddrPort(wire, addr)
				}
				if e != nil {
					done <- e
					return
				}
				if answer.Kind() == v5wire.NodesMsg {
					done <- nil
					return
				}
			}
		}()
		if _, err = client.request(ctx, peer.Node(), &v5wire.Findnode{ReqID: []byte{byte(index)}, Distances: []uint{uint(enode.LogDist(peer.ID(), target.ID()))}}, v5wire.NodesMsg); err != nil {
			t.Fatal(err)
		}
		if e := <-done; e != nil {
			t.Fatal(e)
		}
		var trace *discoveryTrace
		for len(s.events) > 0 {
			if e := <-s.events; e.Trace != nil {
				trace = e.Trace
			}
		}
		if trace == nil || trace.Outcome != "COMPLETE" || !trace.Packets[0].Authenticated {
			t.Fatal("missing authenticated identity")
		}
		if (trace.Observer != nil) != (export != "") {
			t.Fatal("incorrect observer context")
		}
		if export != "" {
			raw, _ := json.Marshal(event{Type: "discoveryTrace", Trace: trace})
			if e := os.WriteFile(export+fmt.Sprint(index)+".json", raw, 0600); e != nil {
				t.Fatal(e)
			}
		}
		if previous != nil && (previous.Endpoint != trace.Endpoint || previous.ResponderIdentity == trace.ResponderIdentity) {
			t.Fatal("endpoint identity conflation")
		}
		previous = trace
	}
}
