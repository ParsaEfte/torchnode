package io.github.gavinruff007.torchnode.model;

public enum NodeType {
    UNKNOWN,
    EXECUTION,      // فقط Execution client (Geth, Nethermind, etc.)
    CONSENSUS,      // فقط Beacon/Consensus client
    FULL_NODE,      // هر دو Execution + Consensus
    RPC_ONLY,       // فقط RPC endpoint بدون P2P
    ARCHIVE         // Archive node با historical data
}
