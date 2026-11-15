package main

// Capture only at the authenticated codec boundary. No endpoint-to-identity inference.
import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"regexp"
	"strings"
	"time"

	"github.com/ethereum/go-ethereum/crypto"
	"github.com/ethereum/go-ethereum/p2p/discover/v5wire"
	"github.com/ethereum/go-ethereum/p2p/enode"
	"github.com/ethereum/go-ethereum/rlp"
)

type observerContext struct {
	InstanceID   string `json:"instanceId"`
	RunID        string `json:"runId"`
	KeyLifecycle string `json:"keyLifecycle"`
}
type enrResult struct {
	Index      int    `json:"index"`
	RLP        string `json:"rlp"`
	Identity   string `json:"identity,omitempty"`
	Validation string `json:"validation"`
}
type packetEvidence struct {
	At              string      `json:"at"`
	Wire            string      `json:"wire"`
	Payload         string      `json:"payload,omitempty"`
	SourceRoutingID string      `json:"sourceRoutingId,omitempty"`
	Authenticated   bool        `json:"authenticated"`
	Status          string      `json:"status"`
	Records         []enrResult `json:"records"`
	OmittedRecords  int         `json:"omittedRecords,omitempty"`
}
type discoveryTrace struct {
	Version            int               `json:"version"`
	TraceID            string            `json:"traceId"`
	Observer           *observerContext  `json:"observer"`
	LocalIdentity      string            `json:"localIdentity"`
	CodecContext       string            `json:"codecContext"`
	RequestID          string            `json:"requestId"`
	RequestPayload     string            `json:"requestPayload"`
	Distances          []uint            `json:"distances"`
	ResponderIdentity  string            `json:"responderIdentity"`
	ResponderENR       string            `json:"responderEnr"`
	ResponderRoutingID string            `json:"responderRoutingId"`
	Endpoint           string            `json:"endpoint"`
	StartedAt          string            `json:"startedAt"`
	CompletedAt        string            `json:"completedAt"`
	Outcome            string            `json:"outcome"`
	FailureCode        string            `json:"failureCode,omitempty"`
	Partial            bool              `json:"partial"`
	Packets            []packetEvidence  `json:"packets"`
	Counts             map[string]uint64 `json:"counts"`
	OmittedPackets     uint64            `json:"omittedPackets"`
	GuardCounts        map[string]uint64 `json:"guardCounts"`
	HelperDrops        uint64            `json:"helperDrops"`
}

func captureToken() string {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		panic("discovery entropy unavailable")
	}
	return hex.EncodeToString(bytes[:])
}
func (c *endpointClient) beginTrace(n *enode.Node, p v5wire.Packet) *discoveryTrace {
	q, ok := p.(*v5wire.Findnode)
	if !ok || c.sink == nil {
		return nil
	}
	record, err := rlp.EncodeToBytes(n.Record())
	if err != nil || n.Pubkey() == nil {
		return nil
	}
	payload, _ := rlp.EncodeToBytes(q)
	endpoint, _ := c.endpoint(n)
	return &discoveryTrace{Version: 2, TraceID: captureToken(), Observer: c.sink.observer,
		LocalIdentity: hex.EncodeToString(crypto.FromECDSAPub(c.local.Node().Pubkey())[1:]), CodecContext: c.contextID,
		RequestID: hex.EncodeToString(q.ReqID), RequestPayload: hex.EncodeToString(payload), Distances: append([]uint(nil), q.Distances...),
		ResponderIdentity: hex.EncodeToString(crypto.FromECDSAPub(n.Pubkey())[1:]), ResponderENR: hex.EncodeToString(record),
		ResponderRoutingID: n.ID().String(), Endpoint: endpoint.String(), StartedAt: time.Now().UTC().Format(time.RFC3339Nano),
		Counts: make(map[string]uint64)}
}
func (t *discoveryTrace) packet(wire []byte, id enode.ID, nodes *v5wire.Nodes, authenticated bool, status string) {
	if t == nil {
		return
	}
	t.Counts[status]++
	if len(t.Packets) >= 8 {
		t.OmittedPackets++
		return
	}
	e := packetEvidence{At: time.Now().UTC().Format(time.RFC3339Nano), Wire: hex.EncodeToString(wire),
		Authenticated: authenticated, Status: status, Records: []enrResult{}}
	if authenticated {
		e.SourceRoutingID = id.String()
	}
	if nodes != nil {
		payload, _ := rlp.EncodeToBytes(nodes)
		e.Payload = hex.EncodeToString(payload)
		if len(nodes.Nodes) > 16 {
			e.OmittedRecords = len(nodes.Nodes) - 16
			t.Counts["EXCESSIVE_ENRS"]++
		}
		for i, r := range nodes.Nodes {
			if i >= 16 {
				break
			}
			raw, err := rlp.EncodeToBytes(r)
			result := enrResult{Index: i, RLP: hex.EncodeToString(raw), Validation: "INVALID_ENR"}
			candidate, validation := enode.New(enode.ValidSchemes, r)
			if err == nil && validation == nil && candidate.Pubkey() != nil {
				result.Identity = hex.EncodeToString(crypto.FromECDSAPub(candidate.Pubkey())[1:])
				result.Validation = "DISTANCE_MISMATCH"
				distance := uint(enode.LogDist(id, candidate.ID()))
				for _, requested := range t.Distances {
					if requested == distance {
						result.Validation = "VALID"
						break
					}
				}
			}
			e.Records = append(e.Records, result)
		}
	}
	t.Packets = append(t.Packets, e)
}
func (c *endpointClient) finishTrace(t *discoveryTrace, err error) {
	if t == nil {
		return
	}
	t.CompletedAt = time.Now().UTC().Format(time.RFC3339Nano)
	t.Outcome = "COMPLETE"
	if err != nil {
		t.Outcome = "INCOMPLETE"
		t.FailureCode = "REQUEST_FAILED"
		t.Partial = t.Counts["MATCHED_NODES"] > 0
		switch {
		case errors.Is(err, context.Canceled):
			t.FailureCode = "CANCELLED"
		case errors.Is(err, context.DeadlineExceeded):
			t.FailureCode = "DEADLINE_EXCEEDED"
		case strings.Contains(err.Error(), "timeout"):
			t.FailureCode = "TIMEOUT"
		case strings.Contains(err.Error(), "excessive"):
			t.FailureCode = "RESPONSE_LIMIT"
		case strings.Contains(err.Error(), "inconsistent"):
			t.FailureCode = "INCONSISTENT_TOTAL"
		}
	}
	t.GuardCounts = c.conn.captureCounts()
	t.HelperDrops = c.sink.dropped.Load()
	e := event{Type: "discoveryTrace", Trace: t}
	// A trace must fit one bounded Java line. Dropped traces cannot produce relationships.
	raw, encodingErr := json.Marshal(e)
	if encodingErr != nil || len(raw) > 60000 {
		c.sink.dropped.Add(1)
		return
	}
	c.sink.tryEmit(e)
}
func nodesFingerprint(nodes *v5wire.Nodes) [32]byte {
	raw, _ := rlp.EncodeToBytes(nodes)
	return sha256.Sum256(raw)
}

var observerIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func validObserverID(value string) bool { return observerIDPattern.MatchString(value) }

func validKeyLifecycle(value string) bool {
	return value == "EPHEMERAL_FIRST_SEEN" || value == "EPHEMERAL_ROTATED" || value == "UNCHANGED"
}
