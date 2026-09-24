package io.github.gavinruff007.torchnode.model;

public class BondState {
        public boolean sentPing;
        public boolean receivedPong;
        public boolean receivedPing;
        public boolean sentPong;

        public  BondState(boolean sentPing, boolean receivedPong, boolean receivedPing, boolean sentPong) {
            this.sentPing = sentPing;
            this.receivedPong = receivedPong;
            this.receivedPing = receivedPing;
            this.sentPong = sentPong;
        }

        public boolean isFullyBonded() {
            return receivedPong && receivedPing && sentPong;
        }
    }